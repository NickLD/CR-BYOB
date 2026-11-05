package com.crbyob.bridge;

/** What an RTSP stream reads frames from: a real UVC node ({@link UvcCapture}) or a {@link TestPattern}. */
interface FrameSource {
    void start() throws java.io.IOException;
    /** Next frame or null after timeoutMs; give it back with release(). */
    UvcCapture.Frame take(long timeoutMs) throws InterruptedException;
    void release(UvcCapture.Frame f);
    long framesCaptured();
    long framesDroppedQueue();
    long uvcErrors();
    void close();
}
