package relay

import "errors"

var (
	errMissingAdminSecret     = errors.New("IDROID_ADMIN_SECRET must be set")
	errUnknownDevice          = errors.New("unknown device")
	errItemNotFound           = errors.New("item not found")
	errPairingCodeInvalid     = errors.New("pairing code invalid or expired")
	errPairingSessionNotFound = errors.New("pairing session not found")
)
