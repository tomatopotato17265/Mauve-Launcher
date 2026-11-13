package main

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/wailsapp/wails/v3/pkg/application"
	"github.com/wailsapp/wails/v3/pkg/events"
)

const (
	signInWindowName    = "signin"
	signInWindowTitle   = "Sign in to Mauve Launcher"
	signInWindowWidth   = 1000
	signInWindowHeight  = 700
	signInWindowMinSize = 500
	signInTimeout       = 10 * time.Minute

	// Prefix of messages the sign-in window posts back to Go.
	signInMessagePrefix = "mauve:signin:"
)

const signInCaptureJS = `(function () {
  try {
    var href = location.href;
    if (href.indexOf("` + authReplyURL + `") !== 0) return;
    var bridge = (window.chrome && window.chrome.webview) ||
      (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.external);
    if (bridge) bridge.postMessage("` + signInMessagePrefix + `" + href);
  } catch (e) {}
})();`

// Account is a signed-in Minecraft account, as shown in the UI.
type Account struct {
	ID       string `json:"id"`
	Username string `json:"username"`
}

type signInResult struct {
	code      string
	err       error
	cancelled bool
}

// open sign-in window waiting for its result
type signInWait struct {
	windowID uint
	result   chan signInResult
}

func (w *signInWait) deliver(r signInResult) {
	select {
	case w.result <- r:
	default:
	}
}

type AuthService struct {
	store  *authStore
	client *xboxClient

	mu      sync.Mutex
	pending *signInWait
}

func NewAuthService() *AuthService {
	return &AuthService{store: newAuthStore(), client: newXboxClient()}
}

func (s *AuthService) GetAccounts() ([]Account, error) {
	stored, err := s.store.accounts()
	if err != nil {
		return nil, err
	}
	out := make([]Account, 0, len(stored))
	for _, a := range stored {
		out = append(out, Account{ID: a.ID, Username: a.Username})
	}
	return out, nil
}

// opens Microsoft's login page in a new window, then exchanges the result for a Minecraft account and saves it
// returns nil (and no error) if the window is closed
func (s *AuthService) Login(ctx context.Context) (*Account, error) {
	key, deviceTok, err := s.deviceToken(ctx)
	if err != nil {
		return nil, err
	}

	flow, err := s.client.sisuAuthenticate(ctx, deviceTok, key)
	if err != nil {
		return nil, err
	}

	res := s.waitForSignIn(ctx, flow.AuthURL)
	if res.cancelled {
		return nil, nil
	}
	if res.err != nil {
		return nil, res.err
	}

	oauth, err := s.client.oauthToken(ctx, res.code, flow.Verifier)
	if err != nil {
		return nil, err
	}
	issued := s.client.now()

	authz, err := s.client.sisuAuthorize(ctx, flow.SessionID, oauth.AccessToken, deviceTok, key)
	if err != nil {
		return nil, err
	}
	xsts, err := s.client.xstsAuthorize(ctx, authz, deviceTok, key)
	if err != nil {
		return nil, err
	}
	mcToken, err := s.client.minecraftToken(ctx, xsts)
	if err != nil {
		return nil, err
	}
	if err := s.client.minecraftEntitlements(ctx, mcToken); err != nil {
		return nil, err
	}
	profile, err := s.client.minecraftProfile(ctx, mcToken)
	if err != nil {
		return nil, err
	}

	acc := storedAccount{
		ID:           profile.ID,
		Username:     profile.Name,
		AccessToken:  mcToken,
		RefreshToken: oauth.RefreshToken,
		Expires:      issued.Add(time.Duration(oauth.ExpiresIn) * time.Second),
	}
	if err := s.store.saveAccount(acc); err != nil {
		return nil, fmt.Errorf("signed in, but couldn't save the account: %w", err)
	}
	return &Account{ID: acc.ID, Username: acc.Username}, nil
}

func (s *AuthService) deviceToken(ctx context.Context) (*deviceKey, string, error) {
	saved, err := s.store.device()
	if err != nil {
		return nil, "", err
	}

	var key *deviceKey
	if saved != nil {
		key, err = parseDeviceKey(saved.ID, saved.PrivateKey)
		if err == nil && saved.Token != "" && saved.NotAfter.After(s.client.now()) {
			return key, saved.Token, nil
		}
	}
	if key == nil {
		if key, err = newDeviceKey(); err != nil {
			return nil, "", err
		}
	}

	tok, err := s.client.deviceToken(ctx, key)
	if err != nil {
		return nil, "", err
	}
	pemKey, err := key.privatePEM()
	if err != nil {
		return nil, "", err
	}
	if err := s.store.saveDevice(&deviceFile{
		ID:         key.ID,
		PrivateKey: pemKey,
		Token:      tok.Token,
		NotAfter:   tok.NotAfter,
	}); err != nil {
		return nil, "", err
	}
	return key, tok.Token, nil
}

func (s *AuthService) waitForSignIn(ctx context.Context, authURL string) signInResult {
	app := application.Get()

	// Only ever show one sign-in window. Closing an old one cancels its Login call.
	if existing, ok := app.Window.GetByName(signInWindowName); ok {
		existing.Close()
	}

	wait := &signInWait{result: make(chan signInResult, 1)}
	window := app.Window.NewWithOptions(application.WebviewWindowOptions{
		Name:      signInWindowName,
		Title:     signInWindowTitle,
		Width:     signInWindowWidth,
		Height:    signInWindowHeight,
		MinWidth:  signInWindowMinSize,
		MinHeight: signInWindowMinSize,
		URL:       authURL,
		JS:        signInCaptureJS,
	})
	wait.windowID = window.ID()

	s.mu.Lock()
	s.pending = wait
	s.mu.Unlock()

	window.OnWindowEvent(events.Common.WindowClosing, func(*application.WindowEvent) {
		wait.deliver(signInResult{cancelled: true})
	})
	// Wails doesn't run WebviewWindowOptions.JS for URL windows on Windows.
	window.OnWindowEvent(events.Windows.WebViewNavigationCompleted, func(*application.WindowEvent) {
		window.ExecJS(signInCaptureJS)
	})
	window.Focus()

	defer func() {
		s.mu.Lock()
		if s.pending == wait {
			s.pending = nil
		}
		s.mu.Unlock()
		window.Close()
	}()

	select {
	case r := <-wait.result:
		return r
	case <-ctx.Done():
		return signInResult{cancelled: true}
	case <-time.After(signInTimeout):
		return signInResult{err: errors.New("Sign-in timed out. Please try again.")}
	}
}

func (s *AuthService) handleSignInMessage(window application.Window, message string, origin *application.OriginInfo) {
	if !strings.HasPrefix(message, signInMessagePrefix) || window == nil || window.Name() != signInWindowName {
		return
	}
	if origin == nil || !isLoginLiveURL(origin.Origin) {
		return
	}

	s.mu.Lock()
	wait := s.pending
	s.mu.Unlock()
	if wait == nil || wait.windowID != window.ID() {
		return
	}

	if r, ok := parseSignInRedirect(strings.TrimPrefix(message, signInMessagePrefix)); ok {
		wait.deliver(r)
	}
}

func isLoginLiveURL(raw string) bool {
	u, err := url.Parse(raw)
	return err == nil && u.Scheme == "https" && u.Host == "login.live.com"
}

// reads the code (or error) from Microsoft's redirect URL
func parseSignInRedirect(raw string) (signInResult, bool) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Host != "login.live.com" || u.Path != "/oauth20_desktop.srf" {
		return signInResult{}, false
	}
	q := u.Query()
	if code := q.Get("code"); code != "" {
		return signInResult{code: code}, true
	}
	if e := q.Get("error"); e != "" {
		if e == "access_denied" {
			return signInResult{cancelled: true}, true
		}
		msg := q.Get("error_description")
		if msg == "" {
			msg = e
		}
		return signInResult{err: fmt.Errorf("Microsoft sign-in failed: %s", msg)}, true
	}
	return signInResult{}, false
}
