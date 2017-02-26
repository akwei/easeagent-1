package com.hexdecteam.easeagent;

import com.google.auto.service.AutoService;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@AutoService(AppendBootstrapClassLoaderSearch.class)
public final class StackFrame {
    private final static ThreadLocal<StackFrame> CURRENT = new ThreadLocal<StackFrame>();

    private final StackFrame       parent;
    private final String           signature;
    private final long             beginTime;
    private final long             beginCPUTime;
    private final List<StackFrame> children;

    private volatile long endTime;
    private volatile long endCPUTime;

    public static boolean setRootIfAbsent(String signature) {
        if (current() != null) return false;

        CURRENT.set(new StackFrame(signature));
        return true;
    }

    public static boolean fork(String signature) {
        final StackFrame frame = current();
        if (frame == null) return false;
        CURRENT.set(fork(frame, signature));
        return true;
    }

    public static StackFrame join() {
        final StackFrame frame = current();
        if (frame == null) return null;
        CURRENT.set(join(frame));
        return frame;
    }

    public static StackFrame current() {
        return CURRENT.get();
    }

    public String getSignature() {
        return signature;
    }

    public List<StackFrame> getChildren() {
        return children;
    }

    public long getExecutionTime() {
        return TimeUnit.NANOSECONDS.toMillis(endTime - beginTime);
    }

    public long getExecutionCPUTime() {
        return TimeUnit.NANOSECONDS.toMillis(endCPUTime - beginCPUTime);
    }

    // TODO remove stagemonitor's legacy
    public long getNetExecutionTime() {
        long net = getExecutionTime();
        for (StackFrame child : children) {
            net -= child.getExecutionTime();
        }
        return net;
    }

    // TODO remove stagemonitor's legacy
    public boolean getIoquery() {
        return false;
    }

    // TODO remove stagemonitor's legacy
    public String getShortSignature() {
        return "TODO @zhongl";
    }

    // TODO remove stagemonitor's legacy
    public String getSignatureRaw() {
        return "TODO @zhongl";
    }

    private static StackFrame fork(StackFrame parent, String name) {
        final StackFrame frame = new StackFrame(name, parent);
        parent.children.add(frame);
        return frame;
    }

    private static StackFrame join(StackFrame frame) {
        frame.endTime = System.nanoTime();
        frame.endCPUTime = CPU_TIME_SUPPORTED ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : 0L;
        return frame.parent;
    }

    private StackFrame(String signature) {
        this(signature, null);
    }

    private StackFrame(String signature, StackFrame parent) {
        this.signature = signature;
        this.parent = parent;
        beginTime = System.nanoTime();
        beginCPUTime = CPU_TIME_SUPPORTED ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : 0L;
        children = new LinkedList<StackFrame>();
    }

    static final ThreadMXBean THREAD_MX_BEAN     = ManagementFactory.getThreadMXBean();
    static final boolean      CPU_TIME_SUPPORTED = THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported();

    static {
        if (THREAD_MX_BEAN.isThreadCpuTimeSupported()) THREAD_MX_BEAN.setThreadCpuTimeEnabled(true);
    }
}