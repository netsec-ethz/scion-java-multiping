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
import org.scion.jpan.internal.PathRawParser;

public class Result {
  public enum State {
    NOT_DONE,
    SUCCESS,
    ERROR,
    NO_PATH,
    TIMEOUT,
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
  private State state = State.NOT_DONE;

  private Result(ParseAssignments.HostEntry e) {
    this.isdAs = e.getIsdAs();
    this.name = e.getName();
  }

  public Result(ParseAssignments.HostEntry e, State state) {
    this(e);
    this.state = state;
  }

  public Result(ParseAssignments.HostEntry e, Scmp.TimedMessage msg, Path request, int nPaths) {
    this(e);
    if (msg == null) {
      state = State.LOCAL_AS;
      return;
    }
    this.nPaths = nPaths;
    this.path = request;
    nHops = PathRawParser.create(request.getRawPath()).getHopCount();
    remoteIP = msg.getPath().getRemoteAddress().getHostAddress();
    if (msg.isTimedOut()) {
      state = State.TIMEOUT;
    } else {
      pingMs = msg.getNanoSeconds() / (double) 1_000_000;
      state = State.SUCCESS;
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

  public boolean isSuccess() {
    return state == State.SUCCESS;
  }

  @Override
  public String toString() {
    String out = ScionUtil.toStringIA(isdAs) + " " + name;
    out += "   " + ScionUtil.toStringPath(path.getMetadata());
    out += "  " + remoteIP + "  nPaths=" + nPaths + "  nHops=" + nHops;
    return out + "  time=" + Util.round(pingMs, 2) + "ms" + "  ICMP=" + icmp;
  }
}
