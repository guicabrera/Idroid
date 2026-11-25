package relay

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// serveFS answers every fs_request the device receives with body, echoing
// the requested path in Content-Disposition so the test can check it.
func serveFS(t *testing.T, srvURL, token, body string) {
	t.Helper()
	wsURL := "ws" + strings.TrimPrefix(srvURL, "http") + "/ws?token=" + token
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	go func() {
		for {
			var event NotifyEvent
			if err := conn.ReadJSON(&event); err != nil {
				return
			}
			if event.Event != "fs_request" {
				continue
			}
			req, _ := http.NewRequest("POST", srvURL+"/fs/respond/"+event.RequestID, strings.NewReader(body))
			req.Header.Set("Authorization", "Bearer "+token)
			req.Header.Set("Content-Type", "text/plain")
			req.Header.Set("Content-Disposition", event.Op+":"+event.Path)
			req.Header.Set("X-Idroid-Status", "200")
			if resp, err := http.DefaultClient.Do(req); err == nil {
				resp.Body.Close()
			}
		}
	}()
}

func TestFSRequestIsRelayedToTarget(t *testing.T) {
	srv := newTestServer(t)
	mac, phone := pair(t, srv, "", "mac", "", "phone")

	if code := call(t, srv, "GET", "/fs/"+mac.ID+"/list?path=/", phone.Token, nil, nil); code != http.StatusServiceUnavailable {
		t.Fatalf("browsing an offline device: %d, want 503", code)
	}

	serveFS(t, srv.URL, mac.Token, "file bytes")
	// The websocket registers asynchronously; wait until the relay sees it.
	for i := 0; i < 100; i++ {
		var resp devicesResponse
		call(t, srv, "GET", "/devices", phone.Token, nil, &resp)
		if len(resp.Devices) == 1 && resp.Devices[0].Online {
			break
		}
	}

	req, _ := http.NewRequest("GET", srv.URL+"/fs/"+mac.ID+"/content?path=/Docs/a.txt", nil)
	req.Header.Set("Authorization", "Bearer "+phone.Token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	got, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK || string(got) != "file bytes" {
		t.Fatalf("content: %d %q", resp.StatusCode, got)
	}
	if cd := resp.Header.Get("Content-Disposition"); cd != "content:/Docs/a.txt" {
		t.Fatalf("request reached the target as %q", cd)
	}

	stranger, _ := pair(t, srv, "", "stranger", "", "stranger-2")
	if code := call(t, srv, "GET", "/fs/"+mac.ID+"/list", stranger.Token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("browsing across groups: %d, want 404", code)
	}
	if code := call(t, srv, "POST", "/fs/respond/nope", phone.Token, nil, nil); code != http.StatusGone {
		t.Fatalf("responding to an unknown request: %d, want 410", code)
	}
}

// serveUploads pulls every upload the device receives and answers with
// "<path>=<body>", the way an app would after writing the file.
func serveUploads(t *testing.T, srvURL, token string) {
	t.Helper()
	wsURL := "ws" + strings.TrimPrefix(srvURL, "http") + "/ws?token=" + token
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	go func() {
		for {
			var event NotifyEvent
			if err := conn.ReadJSON(&event); err != nil {
				return
			}
			if event.Event != "fs_request" || event.Op != "upload" {
				continue
			}
			pull, _ := http.NewRequest("GET", srvURL+"/fs/pull/"+event.RequestID, nil)
			pull.Header.Set("Authorization", "Bearer "+token)
			resp, err := http.DefaultClient.Do(pull)
			if err != nil {
				return
			}
			body, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				body = []byte("pull failed")
			}
			// A second pull of the same upload must be refused.
			again, _ := http.NewRequest("GET", srvURL+"/fs/pull/"+event.RequestID, nil)
			again.Header.Set("Authorization", "Bearer "+token)
			if resp, err := http.DefaultClient.Do(again); err == nil {
				resp.Body.Close()
				if resp.StatusCode != http.StatusGone {
					body = []byte("pulled twice")
				}
			}
			req, _ := http.NewRequest("POST", srvURL+"/fs/respond/"+event.RequestID, strings.NewReader(event.Path+"="+string(body)))
			req.Header.Set("Authorization", "Bearer "+token)
			req.Header.Set("X-Idroid-Status", "201")
			if resp, err := http.DefaultClient.Do(req); err == nil {
				resp.Body.Close()
			}
		}
	}()
}

func TestFSUploadIsPulledByTarget(t *testing.T) {
	srv := newTestServer(t)
	mac, phone := pair(t, srv, "", "mac", "", "phone")
	serveUploads(t, srv.URL, phone.Token)
	for i := 0; i < 100; i++ {
		var resp devicesResponse
		call(t, srv, "GET", "/devices", mac.Token, nil, &resp)
		if len(resp.Devices) == 1 && resp.Devices[0].Online {
			break
		}
	}

	req, _ := http.NewRequest("PUT", srv.URL+"/fs/"+phone.ID+"/upload?path=/Download/a.txt", strings.NewReader("hello"))
	req.Header.Set("Authorization", "Bearer "+mac.Token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	got, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusCreated || string(got) != "/Download/a.txt=hello" {
		t.Fatalf("upload: %d %q", resp.StatusCode, got)
	}

	if code := call(t, srv, "GET", "/fs/"+phone.ID+"/upload?path=/x", mac.Token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("upload with GET: %d, want 404", code)
	}
	if code := call(t, srv, "GET", "/fs/pull/nope", phone.Token, nil, nil); code != http.StatusGone {
		t.Fatalf("pulling an unknown upload: %d, want 410", code)
	}
}

// A command that prints a line and then goes quiet (claude -p): when the
// phone leaves, the target's upload is answered 410 right away, not on its
// next chunk, so it stops the command.
func TestFSExecCallerLeavingStopsTarget(t *testing.T) {
	srv := newTestServer(t)
	mac, phone := pair(t, srv, "", "mac", "", "phone")

	wsURL := "ws" + strings.TrimPrefix(srv.URL, "http") + "/ws?token=" + mac.Token
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	answered := make(chan int, 1)
	go func() {
		var event NotifyEvent
		for event.Event != "fs_request" {
			if err := conn.ReadJSON(&event); err != nil {
				return
			}
		}
		pull, _ := http.NewRequest("GET", srv.URL+"/fs/pull/"+event.RequestID, nil)
		pull.Header.Set("Authorization", "Bearer "+mac.Token)
		if resp, err := http.DefaultClient.Do(pull); err == nil {
			io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
		}
		out, w := io.Pipe()
		defer w.Close()
		go w.Write([]byte("started\n")) // then nothing: the command is thinking
		req, _ := http.NewRequest("POST", srv.URL+"/fs/respond/"+event.RequestID, out)
		req.Header.Set("Authorization", "Bearer "+mac.Token)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			answered <- 0
			return
		}
		resp.Body.Close()
		answered <- resp.StatusCode
	}()
	for i := 0; i < 100; i++ {
		var resp devicesResponse
		call(t, srv, "GET", "/devices", phone.Token, nil, &resp)
		if len(resp.Devices) == 1 && resp.Devices[0].Online {
			break
		}
	}

	ctx, cancel := context.WithCancel(context.Background())
	req, _ := http.NewRequestWithContext(ctx, "PUT", srv.URL+"/fs/"+mac.ID+"/exec", strings.NewReader(`{"command":"claude -p hi"}`))
	req.Header.Set("Authorization", "Bearer "+phone.Token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	line := make([]byte, 8)
	if _, err := io.ReadFull(resp.Body, line); err != nil || string(line) != "started\n" {
		t.Fatalf("first chunk: %q %v", line, err)
	}
	cancel() // the phone closes the tab
	resp.Body.Close()

	select {
	case code := <-answered:
		if code != http.StatusGone && code != 0 {
			t.Fatalf("target's upload answered %d, want 410 (or a dropped connection)", code)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("target kept streaming after the caller left")
	}
}
