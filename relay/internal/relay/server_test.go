package relay

import (
	"bytes"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func newTestServer(t *testing.T) *httptest.Server {
	t.Helper()
	cfg := Config{AdminSecret: "secret", DataDir: t.TempDir(), Retention: time.Hour}
	devices, items, h, pairing, err := NewStores(cfg)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(NewServer(cfg, devices, items, h, pairing))
	t.Cleanup(srv.Close)
	return srv
}

func call(t *testing.T, srv *httptest.Server, method, path, token string, body any, out any) int {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		_ = json.NewEncoder(&buf).Encode(body)
	}
	req, _ := http.NewRequest(method, srv.URL+path, &buf)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil && resp.StatusCode < 300 {
		if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
			t.Fatal(err)
		}
	}
	return resp.StatusCode
}

func upload(t *testing.T, srv *httptest.Server, token, recipient string) int {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	_ = mw.WriteField("recipient", recipient)
	_ = mw.WriteField("kind", "clipboard")
	part, _ := mw.CreateFormFile("payload", "clip.txt")
	_, _ = part.Write([]byte("hello"))
	_ = mw.Close()
	req, _ := http.NewRequest("POST", srv.URL+"/upload", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp.StatusCode
}

// pair has initiatorToken (or a new device named initiatorName) show a code
// that claimerToken (or a new device named claimerName) claims.
func pair(t *testing.T, srv *httptest.Server, initiatorToken, initiatorName, claimerToken, claimerName string) (initiator, claimer DeviceInfo) {
	t.Helper()
	var start struct {
		Device  DeviceInfo `json:"device"`
		Pairing struct {
			Code string `json:"code"`
		} `json:"pairing"`
	}
	if code := call(t, srv, "POST", "/pair/start", initiatorToken, map[string]string{"name": initiatorName}, &start); code != http.StatusCreated {
		t.Fatalf("pair/start: %d", code)
	}
	var claim struct {
		Device DeviceInfo `json:"device"`
	}
	if code := call(t, srv, "POST", "/pair/claim", claimerToken, map[string]string{"code": start.Pairing.Code, "name": claimerName}, &claim); code != http.StatusCreated {
		t.Fatalf("pair/claim: %d", code)
	}
	return start.Device, claim.Device
}

type DeviceInfo struct {
	ID    string `json:"id"`
	Token string `json:"token"`
}

func peers(t *testing.T, srv *httptest.Server, token string) []string {
	t.Helper()
	var resp devicesResponse
	if code := call(t, srv, "GET", "/devices", token, nil, &resp); code != http.StatusOK {
		t.Fatalf("GET /devices: %d", code)
	}
	ids := []string{}
	for _, d := range resp.Devices {
		ids = append(ids, d.ID)
	}
	return ids
}

func TestPairingBuildsOneGroup(t *testing.T) {
	srv := newTestServer(t)

	macMini, phone := pair(t, srv, "", "mac-mini", "", "phone")
	// The already-paired phone joins the MacBook under the same identity.
	macBook, phoneAgain := pair(t, srv, "", "macbook", phone.Token, "")
	if phoneAgain.ID != phone.ID {
		t.Fatalf("phone got a new identity %s, want %s", phoneAgain.ID, phone.ID)
	}

	if got := peers(t, srv, phone.Token); len(got) != 2 {
		t.Fatalf("phone peers = %v, want mac mini and macbook", got)
	}
	if got := peers(t, srv, macBook.Token); len(got) != 2 {
		t.Fatalf("macbook peers = %v, want phone and mac mini", got)
	}

	if code := upload(t, srv, macBook.Token, macMini.ID); code != http.StatusCreated {
		t.Fatalf("upload within group: %d", code)
	}

	stranger, _ := pair(t, srv, "", "stranger", "", "stranger-2")
	if code := upload(t, srv, stranger.Token, phone.ID); code != http.StatusBadRequest {
		t.Fatalf("upload across groups: %d, want 400", code)
	}

	if code := call(t, srv, "DELETE", "/devices/"+macBook.ID, phone.Token, nil, nil); code != http.StatusNoContent {
		t.Fatalf("remove macbook: %d", code)
	}
	if got := peers(t, srv, phone.Token); len(got) != 1 || got[0] != macMini.ID {
		t.Fatalf("phone peers after removal = %v, want only mac mini", got)
	}
	if code := call(t, srv, "GET", "/devices", macBook.Token, nil, nil); code != http.StatusUnauthorized {
		t.Fatalf("removed device token still works: %d", code)
	}
	if code := call(t, srv, "DELETE", "/devices/"+stranger.ID, phone.Token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("removing a device outside the group: %d, want 404", code)
	}
}
