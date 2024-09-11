package org.scion.multiping.util;

import org.scion.jpan.Path;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.Scmp;
import org.scion.jpan.internal.PathRawParser;

public class Result {
    public enum ResultState {
        NOT_DONE,
        DONE,
        ERROR,
        NO_PATH,
        TIME_OUT,
        LOCAL_AS
    }

    private final long isdAs;
    private final String name;
    private int nHops;
    private int nPaths;
    private double pingMs;
    private Path path;
    private String remoteIP;
    private String icmp;
    private ResultState state = ResultState.NOT_DONE;

    private Result(ParseAssignments.HostEntry e) {
        this.isdAs = e.getIsdAs();
        this.name = e.getName();
    }

    public Result(ParseAssignments.HostEntry e, ResultState state) {
        this(e);
        this.state = state;
    }

    public Result(ParseAssignments.HostEntry e, Scmp.TimedMessage msg, Path request, int nPaths) {
        this(e);
        if (msg == null) {
            state = ResultState.LOCAL_AS;
            return;
        }
        this.nPaths = nPaths;
        this.path = request;
        nHops = PathRawParser.create(request.getRawPath()).getHopCount();
        remoteIP = msg.getPath().getRemoteAddress().getHostAddress();
        if (msg.isTimedOut()) {
            state = ResultState.TIME_OUT;
        } else {
            pingMs = msg.getNanoSeconds() / (double) 1_000_000;
            state = ResultState.DONE;
        }
    }

    public long getIsdAs() {
        return isdAs;
    }

    public String getName() {
        return name;
    }

    public void setICMP(String icmp) {
        this.icmp = icmp;
    }

    public int getHopCount() {
        return nHops;
    }

    public int getPathCount() {
        return nPaths;
    }

    public double getPingMs() {
        return pingMs;
    }

    @Override
    public String toString() {
        String out = ScionUtil.toStringIA(isdAs) + " " + name;
        out += "   " + ScionUtil.toStringPath(path.getMetadata());
        out += "  " + remoteIP + "  nPaths=" + nPaths + "  nHops=" + nHops;
        return out + "  time=" + Util.round(pingMs, 2) + "ms" + "  ICMP=" + icmp;
    }
}
