package br.dev.michaellopes.flutter_anycam.camera;

/**
 * Collapses multi-threaded camera lifecycle callbacks into the widget contract:
 * at most one connected, at most one terminal event, nothing after dispose.
 * A failure is often followed by a library "disconnected"; that one must not
 * replace the failure.
 *
 * <p>Those limits hold per session. A camera that can come back on its own (USB replug)
 * starts a new session with {@link #beginSession()}; events tagged with an older session
 * are dropped, so a late teardown of the previous device cannot hide the new connection.</p>
 */
final class CameraEventGate {

    private boolean disposed = false;
    private boolean connected = false;
    private boolean terminal = false;
    private int session = 0;

    synchronized int beginSession() {
        session++;
        connected = false;
        terminal = false;
        return session;
    }

    synchronized boolean isCurrentSession(int session) {
        return !disposed && this.session == session;
    }

    synchronized boolean onConnected() {
        return onConnected(session);
    }

    synchronized boolean onConnected(int session) {
        if (disposed || this.session != session || terminal || connected) {
            return false;
        }
        connected = true;
        return true;
    }

    /** Failed / unauthorized / disconnected all share one terminal slot. */
    synchronized boolean onTerminal() {
        return onTerminal(session);
    }

    synchronized boolean onTerminal(int session) {
        if (disposed || this.session != session || terminal) {
            return false;
        }
        terminal = true;
        return true;
    }

    synchronized boolean isConnected() {
        return connected && !terminal && !disposed;
    }

    synchronized void dispose() {
        disposed = true;
    }

    synchronized boolean isDisposed() {
        return disposed;
    }
}
