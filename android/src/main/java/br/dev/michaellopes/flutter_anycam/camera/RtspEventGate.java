package br.dev.michaellopes.flutter_anycam.camera;

/**
 * Collapses RtspProcessor callbacks (main thread and decoder thread) into the widget
 * contract: at most one connected, at most one terminal event, nothing after dispose.
 * A failure is followed by a library "disconnected"; that one must not replace the failure.
 */
final class RtspEventGate {

    private boolean disposed = false;
    private boolean connected = false;
    private boolean terminal = false;

    synchronized boolean onConnected() {
        if (disposed || terminal || connected) {
            return false;
        }
        connected = true;
        return true;
    }

    /** Failed / unauthorized / disconnected all share one terminal slot. */
    synchronized boolean onTerminal() {
        if (disposed || terminal) {
            return false;
        }
        terminal = true;
        return true;
    }

    synchronized void dispose() {
        disposed = true;
    }

    synchronized boolean isDisposed() {
        return disposed;
    }
}
