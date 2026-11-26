package relay

import (
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Shared mouse and keyboard (ADR-0008): a live, two-way websocket pipe
// between two devices of the same group. The Mac opens GET /input/{phone_id};
// the relay pushes "input_request" (sender_id = Mac) to the phone, which opens
// GET /input/{mac_id}. The two sockets are then bridged frame by frame.
// Nothing is stored or inspected.

// inputWaitTimeout bounds how long one side waits for its peer to join. The
// Mac reconnects on its own, which re-sends the request.
const inputWaitTimeout = 30 * time.Second

// A bridged pipe pings both sides; a side that stays silent (no frame, no
// pong) for inputIdleTimeout is treated as gone and the pipe closes. Without
// this, a phone that vanished without closing TCP keeps the Mac's pointer
// parked on it, since the Mac only returns when the pipe drops.
const (
	inputPingInterval = 10 * time.Second
	inputIdleTimeout  = 30 * time.Second
)

type inputWaiter struct {
	conn   *websocket.Conn
	paired chan *websocket.Conn
}

type inputPipes struct {
	mu      sync.Mutex
	waiting map[string]*inputWaiter    // key "from>to"
	live    map[*websocket.Conn]string // bridged conn -> its device ID
}

var pipes = &inputPipes{waiting: make(map[string]*inputWaiter), live: make(map[*websocket.Conn]string)}

// closeInputPipes drops every pipe the device is on (e.g. it was removed).
func closeInputPipes(deviceID string) {
	pipes.mu.Lock()
	defer pipes.mu.Unlock()
	for conn, id := range pipes.live {
		if id == deviceID {
			conn.Close()
		}
	}
	for key, w := range pipes.waiting {
		if strings.HasPrefix(key, deviceID+">") || strings.HasSuffix(key, ">"+deviceID) {
			w.conn.Close()
			delete(pipes.waiting, key)
		}
	}
}

func (s *Server) handleInput(w http.ResponseWriter, r *http.Request) {
	caller := deviceFromContext(r)
	peerID := r.PathValue("device_id")
	if peerID == caller.ID || !s.devices.SameGroup(caller.ID, peerID) {
		http.Error(w, "unknown device", http.StatusNotFound)
		return
	}
	if !s.hub.IsOnline(peerID) {
		http.Error(w, "device offline", http.StatusServiceUnavailable)
		return
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("input upgrade %s -> %s: %v", caller.ID, peerID, err)
		return
	}

	pipes.mu.Lock()
	if other, ok := pipes.waiting[peerID+">"+caller.ID]; ok {
		delete(pipes.waiting, peerID+">"+caller.ID)
		pipes.mu.Unlock()
		other.paired <- conn
		return // the waiting side's handler owns the bridge
	}
	key := caller.ID + ">" + peerID
	if old, ok := pipes.waiting[key]; ok {
		old.conn.Close()
	}
	me := &inputWaiter{conn: conn, paired: make(chan *websocket.Conn, 1)}
	pipes.waiting[key] = me
	pipes.mu.Unlock()

	s.hub.send(peerID, NotifyEvent{Event: "input_request", SenderID: caller.ID})

	var peer *websocket.Conn
	select {
	case peer = <-me.paired:
	case <-time.After(inputWaitTimeout):
	}
	if peer == nil {
		pipes.mu.Lock()
		if pipes.waiting[key] == me {
			delete(pipes.waiting, key)
		}
		pipes.mu.Unlock()
		// A pairing may have raced the timeout.
		select {
		case peer = <-me.paired:
		default:
			conn.Close()
			return
		}
	}
	log.Printf("input pipe %s <-> %s open", caller.ID, peerID)
	pipes.mu.Lock()
	pipes.live[conn] = caller.ID
	pipes.live[peer] = peerID
	pipes.mu.Unlock()
	bridge(conn, peer)
	pipes.mu.Lock()
	delete(pipes.live, conn)
	delete(pipes.live, peer)
	pipes.mu.Unlock()
	log.Printf("input pipe %s <-> %s closed", caller.ID, peerID)
}

// bridge copies frames both ways until either side closes or goes silent,
// then closes both.
func bridge(a, b *websocket.Conn) {
	done := make(chan struct{}, 2)
	stop := make(chan struct{})
	for _, c := range []*websocket.Conn{a, b} {
		c.SetReadDeadline(time.Now().Add(inputIdleTimeout))
		c.SetPongHandler(func(string) error { return c.SetReadDeadline(time.Now().Add(inputIdleTimeout)) })
		go func() {
			t := time.NewTicker(inputPingInterval)
			defer t.Stop()
			for {
				select {
				case <-stop:
					return
				case <-t.C:
					// WriteControl is safe alongside the pump's writes.
					if c.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second)) != nil {
						c.Close()
						return
					}
				}
			}
		}()
	}
	pump := func(src, dst *websocket.Conn) {
		for {
			kind, data, err := src.ReadMessage()
			if err != nil {
				break
			}
			src.SetReadDeadline(time.Now().Add(inputIdleTimeout))
			if err := dst.WriteMessage(kind, data); err != nil {
				break
			}
		}
		done <- struct{}{}
	}
	go pump(a, b)
	go pump(b, a)
	<-done
	close(stop)
	a.Close()
	b.Close()
	<-done
}
