/**
 * cdp4j Commercial License
 *
 * Copyright 2017, 2019 WebFolder OÜ
 *
 * Permission  is hereby  granted,  to "____" obtaining  a  copy of  this software  and
 * associated  documentation files  (the "Software"), to deal in  the Software  without
 * restriction, including without limitation  the rights  to use, copy, modify,  merge,
 * publish, distribute  and sublicense  of the Software,  and to permit persons to whom
 * the Software is furnished to do so, subject to the following conditions:
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR  IMPLIED,
 * INCLUDING  BUT NOT  LIMITED  TO THE  WARRANTIES  OF  MERCHANTABILITY, FITNESS  FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL  THE AUTHORS  OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE
 * OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.webfolder.cdp.session;

import static io.webfolder.cdp.event.Events.LogEntryAdded;
import static io.webfolder.cdp.event.Events.NetworkResponseReceived;
import static io.webfolder.cdp.event.Events.PageLifecycleEvent;
import static io.webfolder.cdp.event.Events.RuntimeConsoleAPICalled;
import static io.webfolder.cdp.session.WaitUntil.DomReady;
import static io.webfolder.cdp.session.WaitUntil.Load;
import static io.webfolder.cdp.type.constant.ImageFormat.Png;
import static io.webfolder.cdp.type.constant.PdfTransferMode.ReturnAsStream;
import static io.webfolder.cdp.type.network.ResourceType.Document;
import static io.webfolder.cdp.type.network.ResourceType.XHR;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.lang.Math.floor;
import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.lang.ThreadLocal.withInitial;
import static java.lang.reflect.Proxy.newProxyInstance;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.util.Arrays.asList;
import static java.util.Base64.getDecoder;
import static java.util.Locale.ENGLISH;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import com.google.gson.Gson;

import io.webfolder.cdp.JsFunction;
import io.webfolder.cdp.Options;
import io.webfolder.cdp.annotation.Experimental;
import io.webfolder.cdp.annotation.Optional;
import io.webfolder.cdp.channel.Channel;
import io.webfolder.cdp.command.CSS;
import io.webfolder.cdp.command.Emulation;
import io.webfolder.cdp.command.IO;
import io.webfolder.cdp.command.Page;
import io.webfolder.cdp.event.log.EntryAdded;
import io.webfolder.cdp.event.network.ResponseReceived;
import io.webfolder.cdp.event.page.LifecycleEvent;
import io.webfolder.cdp.event.runtime.ConsoleAPICalled;
import io.webfolder.cdp.exception.CdpException;
import io.webfolder.cdp.exception.DestinationUnreachableException;
import io.webfolder.cdp.exception.LoadTimeoutException;
import io.webfolder.cdp.listener.EventListener;
import io.webfolder.cdp.logger.CdpLogger;
import io.webfolder.cdp.logger.LoggerFactory;
import io.webfolder.cdp.type.constant.ImageFormat;
import io.webfolder.cdp.type.css.SourceRange;
import io.webfolder.cdp.type.dom.Rect;
import io.webfolder.cdp.type.io.ReadResult;
import io.webfolder.cdp.type.log.LogEntry;
import io.webfolder.cdp.type.network.Response;
import io.webfolder.cdp.type.page.GetLayoutMetricsResult;
import io.webfolder.cdp.type.page.NavigateResult;
import io.webfolder.cdp.type.page.PrintToPDFResult;
import io.webfolder.cdp.type.page.Viewport;
import io.webfolder.cdp.type.runtime.RemoteObject;

public class Session implements AutoCloseable,
                                Selector     ,
                                Keyboard     ,
                                Mouse        ,
                                Navigator    ,
                                JavaScript   ,
                                Dom          {

    private final Map<Class<?>, Object> commands = new ConcurrentHashMap<>();

    private AtomicBoolean connected = new AtomicBoolean(true);

    private final List<EventListener> listeners;

    private final SessionInvocationHandler invocationHandler;

    private final SessionFactory sesessionFactory;

    private final Channel channel;

    private final CdpLogger log;

    private final CdpLogger logFlow;

    private final Gson gson;

    private final String targetId;

    private final String sessionId;

    private String frameId;

    private Command command;

    private final ReentrantLock lock = new ReentrantLock(true);

    private String browserContextId;

    private volatile Integer executionContextId;

    private final Map<Class<?>, Object> jsFunctions;

    private final Map<Integer, Context> contexts;

    private static final ThreadLocal<Boolean> ENABLE_ENTRY_EXIT_LOG = 
                                                    withInitial(() -> { return TRUE; });

    Session(
            final Options options,
            final Gson gson,
            final String sessionId,
            final String targetId,
            final String browserContextId,
            final Channel channel,
            final Map<Integer, Context> contexts,
            final SessionFactory sessionFactory,
            final List<EventListener> eventListeners,
            final LoggerFactory loggerFactory) {
        this.sessionId         = sessionId;
        this.browserContextId  = browserContextId;
        this.contexts          = contexts;
        this.invocationHandler = new SessionInvocationHandler(
                                                        gson,
                                                        channel,
                                                        contexts,
                                                        this,
                                                        loggerFactory.getLogger("cdp4j.ws.request", options.consoleLoggerLevel()),
                                                        sessionId,
                                                        options.getReadTimeout(),
                                                        options.contextLockType());
        this.targetId         = targetId; 
        this.sesessionFactory = sessionFactory;
        this.listeners        = eventListeners;
        this.channel          = channel;
        this.log              = loggerFactory.getLogger("cdp4j.session", options.consoleLoggerLevel());
        this.logFlow          = loggerFactory.getLogger("cdp4j.flow", options.consoleLoggerLevel());
        this.gson             = gson;
        this.jsFunctions      = new ConcurrentHashMap<>();
        this.command          = new Command(this);
    }

    public String getId() {
        return sessionId;
    }

    /**
     * Close the this browser window
     */
    @Override
    public void close() {
        logEntry("close");
        if (connected.get()) {
            if (channel.isOpen()) {
                try {
                    sesessionFactory.close(this);
                } finally {
                    connected.set(false);
                }
            } else {
                dispose();
            }
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Activate this browser window
     */
    public void activate() {
        logEntry("activate");
        sesessionFactory.activate(sessionId);
    }

    /**
     * Use {@link Session#getListenerManager()}
     */
    public void addEventListener(EventListener eventListener) {
        listeners.add(eventListener);
    }

    /**
     * Use {@link Session#getListenerManager()}
     */
    public void removeEventEventListener(EventListener eventListener) {
        if (eventListener != null) {
            listeners.remove(eventListener);
        }
    }

    /**
     * waits until document is ready
     * 
     * @return this
     */
    public Session waitDocumentReady() {
        return waitDocumentReady(WAIT_TIMEOUT);
    }

    /**
     * waits until document is ready
     * 
     * @param timeout the maximum time to wait in milliseconds
     * 
     * @return this
     */
    public Session waitDocumentReady(final int timeout) {
        return navigateAndWait(null, DomReady, timeout);
    }

    public boolean waitUntil(final Predicate<Session> predicate) {
        return waitUntil(predicate, WAIT_TIMEOUT, WAIT_PERIOD);
    }

    public boolean waitUntil(final Predicate<Session> predicate, final int timeout) {
        return waitUntil(predicate, timeout, WAIT_PERIOD, true);
    }

    public boolean waitUntil(
            final Predicate<Session> predicate,
            final int timeout,
            final int period) {
        return waitUntil(predicate, timeout, period, true);
    }

    public boolean waitUntil(
                    final Predicate<Session> predicate,
                    final int timeout,
                    final int period,
                    final boolean log) {
        final int count = (int) floor(timeout / period);
        for (int i = 0; i < count; i++) {
            final boolean wakeup = predicate.test(getThis());
            if (wakeup) {
                return true;
            } else {
                if ( ! isConnected() ) {
                    return false;
                } else {
                    wait(period, log);
                }
            }
        }
        return false;
    }

    public Session navigate(final String url) {
        logEntry("navigate", url);
        NavigateResult navigate = command.getPage().navigate(url);
        if ( navigate != null ) {
            this.frameId = navigate.getFrameId();
        } else {
            throw new DestinationUnreachableException(url);
        }
        return this;
    }

    public Session navigateAndWait(final String url, WaitUntil condition) {
        return navigateAndWait(url, condition, 10_000);
    }

    public Session navigateAndWait(final String    url,
                                   final WaitUntil condition,
                                   final int       timeout) {

        long start = System.currentTimeMillis();

        final WaitUntil waitUntil =
                            DomReady.equals(condition) ? Load : condition;

        if ( url != null ) {
            logEntry("navigateAndWait",
                                format("[url=%s, waitUntil=%s, timeout=%d]", url, condition.name(), timeout));
            NavigateResult navigate = command.getPage().navigate(url);
            if (navigate == null) {
                throw new DestinationUnreachableException(url);
            }
            this.frameId = navigate.getFrameId();
        }

        CountDownLatch latch = new CountDownLatch(1);

        EventListener loadListener = (e, d) -> {
            if (PageLifecycleEvent.equals(e)) {
                LifecycleEvent le = (LifecycleEvent) d;
                if (waitUntil.value.equals(le.getName())) {
                    latch.countDown();
                }
            }
        };

        addEventListener(loadListener);

        try {
            latch.await(timeout, MILLISECONDS);
        } catch (InterruptedException e) {
            throw new LoadTimeoutException(e);
        } finally {
            removeEventEventListener(loadListener);
        }

        long elapsedTime = System.currentTimeMillis() - start;
        if (elapsedTime > timeout) {
            throw new LoadTimeoutException("Page not loaded within " + timeout + " ms");
        }

        if ( DomReady.equals(condition) && ! isDomReady() ) {
            try {
                disableFlowLog();
                boolean ready = waitUntil(p -> isDomReady(), timeout - (int) elapsedTime, 10);
                if ( ! ready ) {
                    throw new LoadTimeoutException("Page not loaded within " + timeout + " ms");
                }
            } finally {
                enableFlowLog();
            }
        }
        
        return this;
    }

    /**
     * Redirects javascript console logs to slf4j
     * 
     * @return this
     */
    public Session enableConsoleLog() {
        getCommand().getRuntime().enable();
        addEventListener((e, d) -> {
            if (RuntimeConsoleAPICalled.equals(e)) {
                ConsoleAPICalled ca = (ConsoleAPICalled) d;
                for (RemoteObject next : ca.getArgs()) {
                    Object value = next.getValue();
                    String type = ca.getType().toString().toUpperCase(ENGLISH);
                    switch (ca.getType()) {
                        case Log    :
                        case Info   : log.info("[console] [{}] {}", new Object[] { type, valueOf(value) }); break;
                        case Error  : log.info("[console] [{}] {}", new Object[] { type, valueOf(value) }); break;
                        case Warning: log.info("[console] [{}] {}", new Object[] { type, valueOf(value) }); break;
                        default: break;
                    }
                }
            }
        });
        return getThis();
    }

    /**
     * Redirects runtime logs (network, security, storage etc..) to slf4j
     * 
     * @return this
     */
    public Session enableDetailLog() {
        getCommand().getLog().enable();
        addEventListener((e, d) -> {
            if (LogEntryAdded.equals(e)) {
                EntryAdded entryAdded = (EntryAdded) d;
                LogEntry entry = entryAdded.getEntry();
                String level = entry.getLevel().toString().toUpperCase(ENGLISH);
                switch (entry.getLevel()) {
                    case Verbose: log.info("[{}] [{}] {}", entry.getSource(), level, entry.getText()); break;
                    case Info   : log.info("[{}] [{}] {}", entry.getSource(), level, entry.getText()); break;
                    case Warning: log.info("[{}] [{}] {}", entry.getSource(), level, entry.getText()); break;
                    case Error  : log.info("[{}] [{}] {}", entry.getSource(), level, entry.getText()); break;
                }
            }
        });
        return getThis();
    }

    /**
     * Redirects network logs to slf4j
     * 
     * @return this
     */
    public Session enableNetworkLog() {
        getCommand().getNetwork().enable();
        addEventListener((e, d) -> {
            if (NetworkResponseReceived.equals(e)) {
                ResponseReceived rr = (ResponseReceived) d;
                Response         response = rr.getResponse();
                final String     url      = response.getUrl();
                final int        status   = response.getStatus().intValue();
                final String     mimeType = response.getMimeType();
                if (Document.equals(rr.getType()) || XHR.equals(rr.getType())) {
                    log.info("[{}] [{}] [{}] [{}] [{}]", new Object[] {
                        rr.getType().toString().toUpperCase(ENGLISH),
                        rr.getResponse().getProtocol().toUpperCase(ENGLISH),
                        status,
                        mimeType,
                        url
                    });
                }
            }
        });
        return getThis();
    }

    public Session getThis() {
        return this;
    }

    public String getFrameId() {
        return frameId;
    }

    /**
     * Capture page screenshot.
     */
    public byte[] captureScreenshot() {
        return captureScreenshot(false, Png, null, null, true);
    }

    /**
     * Capture page screenshot.
     * 
     * @param hideScrollbar hides the scollbar
     */
    public byte[] captureScreenshot(boolean hideScrollbar) {
        return captureScreenshot(hideScrollbar, Png, null, null, true);
    }

    /**
     * Capture page screenshot.
     * 
     * @param hideScrollbar hides the scollbar
     * @param format Image compression format (defaults to png).
     * @param quality Compression quality from range [0..100] (jpeg only).
     * @param clip Capture the screenshot of a given region only.
     * @param fromSurface Capture the screenshot from the surface, rather than the view. Defaults to true.
     */
    public byte[] captureScreenshot(boolean hideScrollbar,
                                    @Optional ImageFormat format,
                                    @Optional Integer quality,
                                    @Optional Viewport clip,
                                    @Experimental @Optional Boolean fromSurface) {
        SourceRange location = new SourceRange();
        location.setEndColumn(0);
        location.setEndLine(0);
        location.setStartColumn(0);
        location.setStartLine(0);
        String styleSheetId = null;
        if (hideScrollbar) {
            getThis().getCommand().getDOM().enable();
            CSS css = getThis().getCommand().getCSS();
            css.enable();
            styleSheetId = css.createStyleSheet(frameId);
            css.addRule(styleSheetId, "::-webkit-scrollbar { display: none !important; }", location);
        }
        Page page = getThis().getCommand().getPage();
        GetLayoutMetricsResult metrics = page.getLayoutMetrics();
        Rect cs = metrics.getContentSize();
        Emulation emulation = getThis().getCommand().getEmulation();
        emulation.setDeviceMetricsOverride(cs.getWidth().intValue(), cs.getHeight().intValue(), 1D, false);
        byte[] data = page.captureScreenshot(format, quality, clip, fromSurface);
        emulation.clearDeviceMetricsOverride();
        emulation.resetPageScaleFactor();
        if (hideScrollbar) {
            CSS css = getThis().getCommand().getCSS();
            css.setStyleSheetText(styleSheetId, "");
        }
        return data;
    }

    /**
     * Print page as PDF.
     * 
     * <strong>Performance tip</strong>: Prefer to use {@link #printToPDF(Path)} if pdf content is to big.
     * 
     * @return pdf content as a byte array
     */
    public byte[] printToPDF() {
        PrintToPDFResult result = getCommand()
                                    .getPage()
                                    .printToPDF();
        byte[] content = getDecoder().decode(result.getData());
        return content;
    }

    /**
     * Print PDF content to a file
     * 
     * @param file pdf file path
     */
    public void printToPDF(Path file) {
        PrintToPDFResult pdfResult = getCommand()
                                        .getPage()
                                        .printToPDF(null, null,
                                                    null, null,
                                                    null, null,
                                                    null, null,
                                                    null, null,
                                                    null, null,
                                                    null, null,
                                                    null, ReturnAsStream);
        IO io = getCommand().getIO();
        String stream = pdfResult.getStream();
        boolean eof = false;
        try {
            while ( ! eof ) {
                ReadResult streamResult = io.read(stream);
                eof = streamResult.getEof();
                if (streamResult.getBase64Encoded()) {
                    if ( streamResult.getData() != null &&
                            ! streamResult.getData().isEmpty() ) {
                        byte[] content = getDecoder().decode(streamResult.getData());
                        try {
                            Files.write(file, content, APPEND);
                        } catch (IOException e) {
                            throw new CdpException(e);
                        }
                    }
                } else {
                    throw new CdpException("Inavlid content encoding: it must be base64");
                }
            }
        } finally {
            io.close(stream);
        }
    }

    /**
     * Causes the current thread to wait until waiting time elapses.
     * 
     * @param timeout the maximum time to wait in milliseconds
     * 
     * @throws CdpException if the session held by another thread at the time of invocation.
     * 
     * @return this
     */
    public Session wait(int timeout) {
        return wait(timeout, true);
    }

    /**
     * Causes the current thread to wait until waiting time elapses.
     * 
     * @param timeout the maximum time to wait in milliseconds
     * 
     * @throws CdpException if the session held by another thread at the time of invocation.
     * 
     * @return this
     */
    public Session wait(int timeout, boolean log) {
        if (lock.tryLock()) {
            Condition condition = lock.newCondition();
            try {
                if (log) {
                    logEntry("wait", timeout + "ms");
                }
                condition.await(timeout, MILLISECONDS);
            } catch (InterruptedException e) {
                if (channel.isOpen() && connected.get()) {
                    throw new CdpException(e);
                }
            } finally {
                if (lock.isLocked()) {
                    lock.unlock();
                }
            }
        } else {
            throw new CdpException("Unable to acquire lock");
        }
        return getThis();
    }

    public Command getCommand() {
        return command;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((sessionId == null) ? 0 : sessionId.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Session other = (Session) obj;
        if (sessionId == null) {
            if (other.sessionId != null)
                return false;
        } else if (!sessionId.equals(other.sessionId))
            return false;
        return true;
    }
    
    void dispose() {
        commands.clear();
        listeners.clear();
        jsFunctions.clear();
        invocationHandler.dispose();
    }

    Gson getGson() {
        return gson;
    }

    void info(
            final String message,
            final Object ...args) {
        log.info(message, args);
    }

    void error(final String message, final Object ...args) {
        log.error(message, args);
    }

    void logEntry(final String method) {
        logEntry(method, null);
    }

    void logEntry(
            final String method,
            final String args) {
        if ( ! ENABLE_ENTRY_EXIT_LOG.get() ) {
            return;
        }
        boolean hasArgs = args != null ? true : false;
        logFlow.info("{}({}{}{})", new Object[] {
            method,
            hasArgs ? "\"" : "",
            hasArgs ? args : "",
            hasArgs ? "\"" : ""
        });
    }

    void logExit(
            final String method,
            final Object retValue) {
        logExit(method, null, retValue);
    }

    void logExit(
            final String method,
            final String args,
            final Object retValue) {
        if ( ! ENABLE_ENTRY_EXIT_LOG.get() ) {
            return;
        }
        boolean hasArgs = args != null ? true : false;
        logFlow.info("{}({}{}{}): {}", new Object[] {
            method,
            hasArgs ? "\"" : "",
            hasArgs ? args : "",
            hasArgs ? "\"" : "",
            retValue
        });
    }

    @SuppressWarnings("unchecked")
    <T> T getProxy(Class<T> klass) {
        T proxy = (T) commands.get(klass);
        if (proxy != null) {
            return (T) proxy;
        }
        Class<T> implKlass = null;
        try {
            implKlass = (Class<T>) getClass().getClassLoader().loadClass(klass.getName() + "Impl");
            Constructor<T> constructor = implKlass.getConstructor(SessionInvocationHandler.class);
            proxy = constructor.newInstance(invocationHandler);
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException |
                 InstantiationException | IllegalAccessException | IllegalArgumentException |
                 InvocationTargetException e) {
            throw new CdpException(e);
        }
        Object existing = commands.putIfAbsent(klass, proxy);
        if ( existing != null ) {
            return (T) existing;
        }
        return proxy;
    }

    void disableFlowLog() {
        ENABLE_ENTRY_EXIT_LOG.set(FALSE);
    }

    void enableFlowLog() {
        ENABLE_ENTRY_EXIT_LOG.set(TRUE);
    }

    Context getContext(int id) {
        return contexts.get(id);
    }

    List<EventListener> getListeners() {
        return listeners;
    }

    @SuppressWarnings("unchecked")
    public <T> T registerJsFunction(Class<T> klass) {
        if ( ! klass.isInterface() ) {
            throw new CdpException("Class must be interface: " + klass.getName());
        }
        if (asList(klass.getMethods())
                        .stream()
                        .filter(p -> p.isAnnotationPresent(JsFunction.class))
                        .count() == 0) {
            throw new CdpException("Interface must be contain at least one @JsFunction");
        }
        if (jsFunctions.containsKey(klass)) {
            throw new CdpException("Duplicate Registration is not allowed: " + klass);
        }
        if (jsFunctions.keySet()
                        .stream()
                        .filter(p -> p.getSimpleName()
                        .equals(klass.getSimpleName())).count() > 0) {
            throw new CdpException("Duplicate class name is not allowed: " + klass.getSimpleName());            
        }
        Method[] methods = klass.getMethods();
        StringBuilder builder = new StringBuilder();
        builder.append(format("document.%s = document.%s || {};", klass.getSimpleName(), klass.getSimpleName()));
        for (Method next : methods) {
            JsFunction function = next.getAnnotation(JsFunction.class);
            if (function == null) {
                continue;
            }
            StringBuilder jsMethod = new StringBuilder();
            jsMethod.append("document.");
            jsMethod.append(klass.getSimpleName());
            jsMethod.append(".");
            jsMethod.append(next.getName());
            jsMethod.append(" = function(");
            int count = next.getParameterCount();
            StringJoiner joiner = new StringJoiner(", ");
            for (int i = 0; i < count; i++) {
                Parameter parameter = next.getParameters()[i];
                joiner.add(parameter.getName());
            }
            jsMethod.append(joiner.toString());
            jsMethod.append(") { ");
            jsMethod.append(function.value());
            jsMethod.append(" };");
            builder.append(jsMethod.toString());
        }
        Page page = getCommand().getPage();
        page.enable();
        page.addScriptToEvaluateOnNewDocument(builder.toString());
        Object instance = newProxyInstance(getClass().getClassLoader(),
                                            new Class<?>[] { klass },
                                            (InvocationHandler) (proxy, method, args) -> {
            String className = method.getDeclaringClass().getSimpleName();
            String methodName = method.getName();
            Class<?> returnType = method.getReturnType();
            if ((void.class.equals(returnType) || Void.class.equals(returnType)) && (args == null || args.length == 0)) {
                callFunction("document." + className + "." + methodName);
                return null;
            } else {
                Object result = callFunction("document." + className + "." + methodName, returnType, args);
                return result;
            }
        });
        jsFunctions.put(klass, instance);
        return (T) instance;
    }

    @SuppressWarnings("unchecked")
    public <T> T getJsFunction(Class<T> klass) {
        return (T) jsFunctions.get(klass);
    }

    boolean isPrimitive(Class<?> klass) {
        if (String.class.equals(klass)) {
            return true;
        } else if (boolean.class.equals(klass) || Boolean.class.equals(klass)) {
            return true;
        } else if (void.class.equals(klass) || Void.class.equals(klass)) {
            return true;
        } else if (int.class.equals(klass) || Integer.class.equals(klass)) {
            return true;
        } else if (double.class.equals(klass) || Double.class.equals(klass)) {
            return true;
        } else if (long.class.equals(klass) || Long.class.equals(klass)) {
            return true;
        } else if (float.class.equals(klass) || Float.class.equals(klass)) {
            return true;
        } else if (char.class.equals(klass) || Character.class.equals(klass)) {
            return true;
        } else if (byte.class.equals(klass) || Byte.class.equals(klass)) {
            return true;
        } else if (short.class.equals(klass) || Short.class.equals(klass)) {
            return true;
        }
        return false;
    }

    public String getTargetId() {
        return targetId;
    }
    
    public String getBrowserContextId() {
        return browserContextId;
    }

    public Integer getExecutionContextId() {
        return executionContextId;
    }

    void setExecutionContextId(Integer executionContextId) {
        this.executionContextId = executionContextId;
    }

    @Override
    public String toString() {
        return "Session [sessionId=" + sessionId + "]";
    }
}
