// tsrelay v2 —— 眼镜上的常驻中继（纯眼镜版 Tailscale + 收流转发）
//
// 干两件事：
//  1) 端口转发：把本机监听端口的 TCP 字节流转发到 tailnet 目标（MAPS 环境变量）。
//  2) 收流转发：接收眼镜端 App POST 上来的 H.264 Annex-B 帧（INGEST，默认 :8899/h264），
//     经 tailnet 用 RTMP 推给接收站（PUBLISH，默认 rtmp://YOUR-SERVER:1935/rokid）。
//
// 为什么需要它：眼镜固件切到 live_broadcast 场景时会把所有第三方 App force-stop
// （logcat: ThirdAppScene -> forceStopPackage success: com.tailscale.ipn），官方 Tailscale 的
// VpnService 被杀 → 系统 VPN 断。本程序用 tsnet（用户态 Tailscale，不需要 VpnService、不需要 root）
// 且以 shell 进程身份运行 —— 系统按包名杀 App，抓不到 shell 进程。
//
// 用法（眼镜上，shell 身份；环境变量见 ~/rokid-dev/tsrelay/start.sh）：
//   MAPS="0.0.0.0:1935=YOUR-SERVER:1935" INGEST="0.0.0.0:8899" \
//   PUBLISH="rtmp://YOUR-SERVER:1935/rokid" ./tsrelay
package main

import (
	"bufio"
	"context"
	"os/exec"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/bluenviron/gortmplib"
	"github.com/bluenviron/gortmplib/pkg/codecs"
	"tailscale.com/tsnet"
)

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	authKey := os.Getenv("TS_AUTHKEY")
	stateDir := getenv("TS_STATE", "/data/local/tmp/tsrelay/state")
	hostname := getenv("TS_HOSTNAME", "glasses-relay")
	mapsSpec := getenv("MAPS", "")
	ingestAddr := getenv("INGEST", "0.0.0.0:8899")
	publishURL := getenv("PUBLISH", "rtmp://YOUR-SERVER:1935/rokid")

	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
	log.Printf("tsrelay 启动: hostname=%s state=%s maps=%q ingest=%s publish=%s",
		hostname, stateDir, mapsSpec, ingestAddr, publishURL)

	s := &tsnet.Server{
		Hostname: hostname,
		AuthKey:  authKey,
		Dir:      stateDir,
		Logf:     log.Printf,
	}
	defer s.Close()

	for _, m := range strings.Split(mapsSpec, ",") {
		m = strings.TrimSpace(m)
		if m == "" {
			continue
		}
		parts := strings.SplitN(m, "=", 2)
		if len(parts) != 2 {
			log.Printf("忽略无效映射: %q", m)
			continue
		}
		go serveTCP(s, strings.TrimSpace(parts[0]), strings.TrimSpace(parts[1]))
	}

	pub := newPublisher(s, publishURL)
	go pub.run()
	go pub.idleWatch()
	go pub.statsWatch()
	go pub.watchdog()

	rawAddr := getenv("RAW_INGEST", "0.0.0.0:8900")
	go serveRawIngest(rawAddr, pub)

	if err := serveIngest(ingestAddr, pub); err != nil {
		log.Printf("收流服务启动失败: %v", err)
		os.Exit(1)
	}
	select {}
}

// ---------------- 端口转发 ----------------

func serveTCP(s *tsnet.Server, listen, target string) {
	for {
		ln, err := net.Listen("tcp", listen)
		if err != nil {
			log.Printf("监听 %s 失败: %v（5 秒后重试）", listen, err)
			time.Sleep(5 * time.Second)
			continue
		}
		log.Printf("监听 %s -> %s（走 tailnet）", listen, target)
		for {
			c, err := ln.Accept()
			if err != nil {
				log.Printf("accept %s 出错: %v", listen, err)
				break
			}
			go proxyTCP(s, c, target)
		}
		ln.Close()
		time.Sleep(time.Second)
	}
}

func proxyTCP(s *tsnet.Server, c net.Conn, target string) {
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	rc, err := s.Dial(ctx, "tcp", target)
	if err != nil {
		log.Printf("经 tailnet 连 %s 失败: %v", target, err)
		return
	}
	defer rc.Close()
	log.Printf("转发 %s <-> %s", c.RemoteAddr(), target)
	done := make(chan struct{}, 2)
	go func() { io.Copy(rc, c); done <- struct{}{} }()
	go func() { io.Copy(c, rc); done <- struct{}{} }()
	<-done
}

// ---------------- 收流（App POST H.264）→ RTMP 推给接收站 ----------------

type frame struct {
	au  [][]byte // NALUs（不含 SPS/PPS/AUD）
	key bool
	pts time.Duration
	sps []byte
	pps []byte
}

type publisher struct {
	s       *tsnet.Server
	urlStr  string
	queue   chan frame
	mu      sync.Mutex
	lastAt  time.Time
	started bool
	inFrames   uint64
	dropFrames uint64
	inBytes    uint64
	armed      bool // App 正在推流（收到过帧、且没收到"主动停止"的 bye）

	client *gortmplib.Client
	writer *gortmplib.Writer
	track  *gortmplib.Track
	baseTS time.Duration
	haveTS bool
	curSPS []byte
	curPPS []byte
}

func newPublisher(s *tsnet.Server, urlStr string) *publisher {
	return &publisher{s: s, urlStr: urlStr, queue: make(chan frame, 120)}
}

func (p *publisher) offer(f frame) bool {
	p.mu.Lock()
	p.lastAt = time.Now()
	p.started = true
	p.inFrames++
	for _, n := range f.au {
		p.inBytes += uint64(len(n))
	}
	p.mu.Unlock()
	select {
	case p.queue <- f:
		return true
	default:
		p.mu.Lock()
		p.dropFrames++
		p.mu.Unlock()
		return false // 队列满：丢帧，别把采集端拖死
	}
}

func (p *publisher) params() ([]byte, []byte) {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.curSPS, p.curPPS
}

// noteParams 把刚收到的 SPS/PPS 立刻记下来（黏住），免得推送协程还没连上时后续帧被当 "wait-sps" 丢掉。
func (p *publisher) noteParams(sps, pps []byte) ([]byte, []byte) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(sps) > 0 {
		p.curSPS = append([]byte(nil), sps...)
	}
	if len(pps) > 0 {
		p.curPPS = append([]byte(nil), pps...)
	}
	return p.curSPS, p.curPPS
}

// watchdog：App 被系统 lowmemorykiller 杀掉时会「raw 连接断了且没发 bye」，
// 这时自动把 App 拉起来接着推流；用户主动停止（收到 bye）不会触发。
func (p *publisher) watchdog() {
	var lastRestart time.Time
	for range time.Tick(5 * time.Second) {
		p.mu.Lock()
		armed, last := p.armed, p.lastAt
		p.mu.Unlock()
		if !armed || last.IsZero() || time.Since(last) < 20*time.Second {
			continue
		}
		if time.Since(lastRestart) < 60*time.Second {
			continue
		}
		lastRestart = time.Now()
		log.Printf("看门狗：%.0f 秒没有帧（App 可能被杀），拉起 App", time.Since(last).Seconds())
		out, err := exec.Command("am", "start", "-n", "com.takano.rstream/.MainActivity",
			"--ez", "start", "true").CombinedOutput()
		if err != nil {
			log.Printf("看门狗拉起失败: %v %s", err, string(out))
		} else {
			log.Printf("看门狗已发出启动命令")
		}
	}
}

// statsWatch 每 5 秒打一行收流统计（判断"运动时画质差"是链路丢帧还是编码器本身）
func (p *publisher) statsWatch() {
	var lastIn, lastDrop, lastBytes uint64
	var lastAt = time.Now()
	for range time.Tick(5 * time.Second) {
		p.mu.Lock()
		in, drop, bytes := p.inFrames, p.dropFrames, p.inBytes
		q := len(p.queue)
		p.mu.Unlock()
		dt := time.Since(lastAt).Seconds()
		if dt > 0 {
			log.Printf("收流统计: 帧=%d(+%d) 丢=%d(+%d) 码率=%.0fkbps 队列=%d",
				in, in-lastIn, drop, drop-lastDrop,
				float64(bytes-lastBytes)*8/1000.0/dt, q)
		}
		lastIn, lastDrop, lastBytes, lastAt = in, drop, bytes, time.Now()
	}
}

func (p *publisher) idleWatch() {
	for range time.Tick(2 * time.Second) {
		p.mu.Lock()
		idle := p.started && time.Since(p.lastAt) > 10*time.Second
		p.mu.Unlock()
		if idle && p.client != nil {
			log.Printf("10 秒没有新帧，断开 RTMP 推流")
			p.close()
		}
	}
}

func (p *publisher) isArmed() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.armed
}

func (p *publisher) close() {
	safeClose(p.client)
	p.client = nil
	p.writer = nil
	p.track = nil
	p.mu.Lock()
	p.started = false
	p.mu.Unlock()
}

func (p *publisher) run() {
	for f := range p.queue {
		if !p.haveTS {
			p.baseTS = f.pts
			p.haveTS = true
		}
		pts := f.pts - p.baseTS
		if pts < 0 {
			pts = 0
		}
		// SPS/PPS 变了（分辨率变化等）→ 重连一次，让接收端拿到新的 sequence header
		if p.client != nil && (!bytesEq(p.curSPS, f.sps) || !bytesEq(p.curPPS, f.pps)) {
			log.Printf("SPS/PPS 变化，重连 RTMP")
			p.close()
		}
		if p.client == nil {
			if err := p.connect(f.sps, f.pps); err != nil {
				log.Printf("RTMP 连接失败: %v（丢帧，等下帧再试）", err)
				continue
			}
		}
		if err := p.writer.WriteH264(p.track, pts, pts, f.au); err != nil {
			log.Printf("写 RTMP 失败: %v（重连）", err)
			p.close()
			continue
		}
	}
}

func (p *publisher) connect(sps, pps []byte) error {
	u, err := url.Parse(p.urlStr)
	if err != nil {
		return err
	}
	c := &gortmplib.Client{
		URL:     u,
		Publish: true,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			dctx, cancel := context.WithTimeout(ctx, 20*time.Second)
			defer cancel()
			return p.s.Dial(dctx, network, addr)
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := c.Initialize(ctx); err != nil {
		// 注意：Initialize 失败时内部 conn 为 nil，Close() 会 panic，直接返回
		return err
	}
	track := &gortmplib.Track{Codec: &codecs.H264{SPS: sps, PPS: pps}}
	w := &gortmplib.Writer{Conn: c, Tracks: []*gortmplib.Track{track}}
	if err := w.Initialize(); err != nil {
		safeClose(c)
		return err
	}
	p.client = c
	p.writer = w
	p.track = track
	p.mu.Lock()
	p.curSPS = append([]byte(nil), sps...)
	p.curPPS = append([]byte(nil), pps...)
	p.mu.Unlock()
	log.Printf("RTMP 已连上 %s（SPS %d 字节 / PPS %d 字节）", p.urlStr, len(sps), len(pps))
	return nil
}

// safeClose gortmplib.Client 在 Initialize 失败（内部 conn 还是 nil）时 Close 会 panic，这里兜一下。
func safeClose(c *gortmplib.Client) {
	if c == nil {
		return
	}
	defer func() { _ = recover() }()
	c.Close()
}

func bytesEq(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// serveRawIngest 原始 TCP 收流：帧头 13 字节（4 字节长度 + 1 字节标志(bit0=关键帧) + 8 字节时间戳µs）+ Annex-B 数据。
// 比"每帧一个 HTTP 请求"快得多：不用等响应，也就没有 Nagle/延迟 ACK 那 40ms 的卡顿。
func serveRawIngest(addr string, p *publisher) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		log.Printf("原始收流监听 %s 失败: %v", addr, err)
		return
	}
	log.Printf("原始收流监听 %s（13 字节帧头 + Annex-B）", addr)
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Printf("原始收流 accept 出错: %v", err)
			return
		}
		go handleRawConn(c, p)
	}
}

func handleRawConn(c net.Conn, p *publisher) {
	defer c.Close()
	if tcp, ok := c.(*net.TCPConn); ok {
		tcp.SetNoDelay(true)
	}
	log.Printf("原始收流连接来自 %s", c.RemoteAddr())
	p.mu.Lock()
	p.armed = true
	p.mu.Unlock()
	r := bufio.NewReaderSize(c, 1<<20)
	hdr := make([]byte, 13)
	for {
		if _, err := io.ReadFull(r, hdr); err != nil {
			log.Printf("原始收流断开 %s: %v（看门狗 %s）", c.RemoteAddr(), err,
				map[bool]string{true: "待命", false: "关闭"}[p.isArmed()])
			return
		}
		n := binary.BigEndian.Uint32(hdr[0:4])
		flags := hdr[4]
		tsUs := int64(binary.BigEndian.Uint64(hdr[5:13]))
		if n == 0 {
			// 长度 0 = App 主动停止推流，别让看门狗再把它拉起来
			p.mu.Lock()
			p.armed = false
			p.mu.Unlock()
			log.Printf("收到主动停止信号，关闭看门狗")
			return
		}
		if n > 8<<20 {
			log.Printf("原始收流帧长度异常 %d，断开", n)
			return
		}
		buf := make([]byte, n)
		if _, err := io.ReadFull(r, buf); err != nil {
			log.Printf("原始收流读帧失败: %v", err)
			return
		}
		nals, sps, pps := splitAnnexB(buf)
		if len(nals) == 0 {
			continue
		}
		curSPS, curPPS := p.noteParams(sps, pps)
		if len(curSPS) == 0 || len(curPPS) == 0 {
			continue
		}
		p.offer(frame{
			au:  nals,
			key: flags&1 == 1,
			pts: time.Duration(tsUs) * time.Microsecond,
			sps: curSPS,
			pps: curPPS,
		})
	}
}

func serveIngest(addr string, p *publisher) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/h264", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, 8<<20))
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		key := r.Header.Get("X-Key") == "1"
		var tsUs int64
		if v := r.Header.Get("X-Frame-Ts"); v != "" {
			tsUs, _ = strconv.ParseInt(v, 10, 64)
		}
		nals, sps, pps := splitAnnexB(body)
		if len(nals) == 0 {
			fmt.Fprint(w, "empty")
			return
		}
		curSPS, curPPS := p.noteParams(sps, pps)
		if len(curSPS) == 0 || len(curPPS) == 0 {
			fmt.Fprint(w, "wait-sps")
			return
		}
		if !p.offer(frame{
			au:  nals,
			key: key,
			pts: time.Duration(tsUs) * time.Microsecond,
			sps: curSPS,
			pps: curPPS,
		}) {
			fmt.Fprint(w, "dropped")
			return
		}
		fmt.Fprint(w, "ok")
	})
	// 其他路径（App 的 JPEG 模式等）一律回 200，免得采集端疯狂重试
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		fmt.Fprint(w, "ok")
	})
	log.Printf("收流服务监听 %s（POST /h264，Annex-B）", addr)
	srv := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	return srv.ListenAndServe()
}

// splitAnnexB 把 Annex-B 码流切成 NALU 列表，并单独返回 SPS/PPS。
// 返回的 NALU 已剔除 AUD(9) 与 SPS(7)/PPS(8)（后两者进 RTMP 的 sequence header）。
func splitAnnexB(b []byte) ([][]byte, []byte, []byte) {
	var nals [][]byte
	var sps, pps []byte
	i := 0
	for i < len(b) {
		start, scLen := findStartCode(b, i)
		if start < 0 {
			break
		}
		nalStart := start + scLen
		next, _ := findStartCode(b, nalStart)
		if next < 0 {
			next = len(b)
		}
		nal := b[nalStart:next]
		if len(nal) > 0 {
			switch nal[0] & 0x1F {
			case 7:
				sps = append([]byte(nil), nal...)
			case 8:
				pps = append([]byte(nil), nal...)
			case 9: // AUD：丢掉
			default:
				nals = append(nals, append([]byte(nil), nal...))
			}
		}
		i = next
		if next >= len(b) {
			break
		}
	}
	return nals, sps, pps
}

// findStartCode 从 off 开始找下一个起始码，返回起始位置与长度（3 或 4），找不到返回 -1。
func findStartCode(b []byte, off int) (int, int) {
	for j := off; j+3 <= len(b); j++ {
		if b[j] == 0 && b[j+1] == 0 {
			if b[j+2] == 1 {
				return j, 3
			}
			if j+4 <= len(b) && b[j+2] == 0 && b[j+3] == 1 {
				return j, 4
			}
		}
	}
	return -1, 0
}
