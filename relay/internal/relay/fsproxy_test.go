package relay

import (
	"io"
	"net/http"
	"strings"
	"testing"

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
