package relay

import (
	"crypto/rand"
	"fmt"
	"math/big"
	"sync"
	"time"
)

// pairingTTL bounds how long an unclaimed code is valid, and (doubled) how
// long a claimed session stays around for the initiator to poll and collect
// the result. Short on purpose: the code is the only secret standing in for
// the admin token in this flow (see handlePairStart/handlePairClaim in
// server.go), so a tight window limits how long it's guessable.
const pairingTTL = 5 * time.Minute

// PairingSession represents one in-progress device pairing, replacing the
// need for an operator to run `POST /admin/devices` by hand twice and copy
// tokens around manually (ADR-0001's "pairing UX lives in the client apps"
// gap). The device that calls /pair/start mints its own token immediately
// and shows the resulting code to the user (read aloud, typed in, or later
// a QR code); the other device calls /pair/claim with that code to mint its
// own token and learn who it's now paired with.
type PairingSession struct {
	SessionID    string
	Code         string
	DeviceID     string
	ExpiresAt    time.Time
	Claimed      bool
	PeerDeviceID string
	PeerName     string
}

type pairingStore struct {
	mu        sync.Mutex
	byCode    map[string]*PairingSession
	bySession map[string]*PairingSession
}

func newPairingStore() *pairingStore {
	return &pairingStore{
		byCode:    make(map[string]*PairingSession),
		bySession: make(map[string]*PairingSession),
	}
}

// Start begins a new pairing session for deviceID, generating a short code
// that must be unique among currently outstanding (unclaimed) codes.
func (p *pairingStore) Start(deviceID string) (*PairingSession, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.sweep()

	sessionID, err := newID(16)
	if err != nil {
		return nil, fmt.Errorf("generate session id: %w", err)
	}
	code, err := p.generateUniqueCodeLocked()
	if err != nil {
		return nil, err
	}

	session := &PairingSession{
		SessionID: sessionID,
		Code:      code,
		DeviceID:  deviceID,
		ExpiresAt: time.Now().Add(pairingTTL),
	}
	p.byCode[code] = session
	p.bySession[sessionID] = session
	return session, nil
}

// ClaimCode validates and immediately consumes a code so a concurrent claim
// can't reuse it, then returns the session for the caller to attach peer
// info to via SetPeer once the second device is registered.
func (p *pairingStore) ClaimCode(code string) (*PairingSession, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.sweep()

	session, ok := p.byCode[code]
	if !ok || session.Claimed || time.Now().After(session.ExpiresAt) {
		return nil, errPairingCodeInvalid
	}
	session.Claimed = true
	delete(p.byCode, code)
	return session, nil
}

func (p *pairingStore) SetPeer(sessionID, peerDeviceID, peerName string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if session, ok := p.bySession[sessionID]; ok {
		session.PeerDeviceID = peerDeviceID
		session.PeerName = peerName
	}
}

// Status is polled by the initiating device to learn when the other side
// has claimed the code.
func (p *pairingStore) Status(sessionID string) (*PairingSession, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	session, ok := p.bySession[sessionID]
	if !ok {
		return nil, errPairingSessionNotFound
	}
	return session, nil
}

// sweep assumes the caller holds p.mu. Claimed sessions get a second TTL
// window (instead of being deleted the moment they're claimed) so the
// initiator has time to poll and see the result.
func (p *pairingStore) sweep() {
	now := time.Now()
	for code, s := range p.byCode {
		if !s.Claimed && now.After(s.ExpiresAt) {
			delete(p.byCode, code)
		}
	}
	for id, s := range p.bySession {
		if now.After(s.ExpiresAt.Add(pairingTTL)) {
			delete(p.bySession, id)
		}
	}
}

// generateUniqueCodeLocked assumes the caller holds p.mu.
func (p *pairingStore) generateUniqueCodeLocked() (string, error) {
	for attempt := 0; attempt < 20; attempt++ {
		n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
		if err != nil {
			return "", fmt.Errorf("generate pairing code: %w", err)
		}
		code := fmt.Sprintf("%06d", n.Int64())
		if _, taken := p.byCode[code]; !taken {
			return code, nil
		}
	}
	return "", fmt.Errorf("could not generate a unique pairing code")
}
