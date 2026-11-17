package bridgectl

// SelfTestResult contains only capability outcomes, never paths, identifiers,
// ciphertext, or secrets. Native Windows tests still need a Windows host.
type SelfTestResult struct {
	Supported bool            `json:"supported"`
	OK        bool            `json:"ok"`
	Checks    map[string]bool `json:"checks"`
}
