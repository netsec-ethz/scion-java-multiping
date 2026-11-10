// Copyright 2024 ETH Zurich
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scion.multiping.util;

import org.scion.jpan.Path;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.Scmp;
import org.scion.jpan.internal.header.PathRawParser;

public class Result {
  public enum State {
    NOT_DONE, SUCCESS, ERROR, NO_PATH, TIMEOUT, LOCAL_AS
  }

  private final long isdAs;
  private final String name;
  private int nHops;
  private int nPaths;
  private double pingMs;
  private Scmp.TimedMessage bestMsg;
  private Scmp.TimedMessage[] bestMsgPerRound;
  private Path path;
  private String remoteIP;
  private String icmp;
  private State state = State.NOT_DONE;

  public static Result createDummy() {
    return new Result(new ParseAssignments.HostEntry(0, "dummy"), State.ERROR);
  }

  public static Result createError(ParseAssignments.HostEntry e) {
    return new Result(e, State.ERROR);
  }

  public static Result createNoPath(ParseAssignments.HostEntry e) {
    return new Result(e, State.NO_PATH);
  }

  public static Result createUnfinished(ParseAssignments.HostEntry e, int nPaths, int nRounds) {
    Result result = new Result(e, State.NOT_DONE);
    result.nPaths = nPaths;
    result.bestMsgPerRound = new Scmp.TimedMessage[nRounds];
    return result;
  }

  private Result(ParseAssignments.HostEntry e) {
    this.isdAs = e.getIsdAs();
    this.name = e.getName();
  }

  private Result(ParseAssignments.HostEntry e, State state) {
    this(e);
    this.state = state;
  }

  public Result(ParseAssignments.HostEntry e, Scmp.TimedMessage msg, int nPaths) {
    this(e);
    if (msg == null) {
      state = State.LOCAL_AS;
      return;
    }
    this.nPaths = nPaths;
    Path request = msg.getRequest() == null ? msg.getPath() : msg.getRequest().getPath();
    this.path = request;
    nHops = request != null ? PathRawParser.create(request.getRawPath()).getHopCount() : -1;
    remoteIP = msg.getPath().getRemoteAddress().getHostAddress();
    if (msg.isTimedOut()) {
      state = State.TIMEOUT;
    } else {
      pingMs = msg.getNanoSeconds() / (double) 1_000_000;
      state = State.SUCCESS;
    }
  }

  public void finish() {
    for (int i = 0; i < bestMsgPerRound.length; i++) {
      Scmp.TimedMessage msg = bestMsgPerRound[i];
      if (msg != null && (bestMsg == null || msg.getNanoSeconds() < bestMsg.getNanoSeconds())) {
        bestMsg = msg;
      }
    }

    if (bestMsg == null) {
      state = State.ERROR;
      return;
    }
    if (bestMsg.isTimedOut()) {
      path = bestMsg.getPath();
      state = State.TIMEOUT;
    } else {
      path = bestMsg.getRequest().getPath();
      pingMs = bestMsg.getNanoSeconds() / (double) 1_000_000;
      state = State.SUCCESS;
    }
    nHops = PathRawParser.create(path.getRawPath()).getHopCount();
    remoteIP = bestMsg.getPath().getRemoteAddress().getHostAddress();
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

  public String getRemoteIP() {
    return remoteIP;
  }

  public boolean isSuccess() {
    return state == State.SUCCESS;
  }

  public boolean isTimedOut() {
    return state == State.TIMEOUT;
  }

  public boolean isError() {
    return state == State.ERROR;
  }

  public void checkBestPath(Scmp.TimedMessage msg, int round) {
    if (bestMsgPerRound[round] == null) {
      bestMsgPerRound[round] = msg;
    } else {
      if (msg.getNanoSeconds() < bestMsgPerRound[round].getNanoSeconds()) {
        bestMsgPerRound[round] = msg;
      }
    }
  }

  public Path getBestPath() {
    return path;
  }

  public Scmp.TimedMessage[] getBestMessages() {
    return bestMsgPerRound;
  }

  @Override
  public String toString() {
    String out = ScionUtil.toStringIA(isdAs) + " " + name;
    String p;
    if (path == null) {
      p = "N/A";
    } else if (path.getMetadata() == null) {
      p = ScionUtil.toStringPath(path.getRawPath());
    } else {
      p = ScionUtil.toStringPath(path.getMetadata());
    }
    out += "   " + p;
    out += "  " + remoteIP + "  nPaths=" + nPaths + "  nHops=" + nHops;
    return out + "  time=" + Util.round(pingMs, 2) + "ms" + "  ICMP=" + icmp;
  }
}
