package relay

import (
	"crypto/rand"
	"encoding/hex"
)

// newID returns a random hex identifier suitable for item IDs and device tokens.
func newID(numBytes int) (string, error) {
	buf := make([]byte, numBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}
