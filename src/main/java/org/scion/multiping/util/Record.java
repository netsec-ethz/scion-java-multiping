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

import static org.scion.multiping.util.Util.round;

import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import org.scion.jpan.Path;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.Scmp;
import org.scion.jpan.internal.PathRawParser;

public class Record {
  private final long isdAs;
  private final ArrayList<Attempt> attempts = new ArrayList<>();
  private final Instant time;
  private final Path path;
  private String remoteIP;
  private String icmp;

  public Record(Instant time, Path request) {
    this.isdAs = request.getRemoteIsdAs();
    this.time = time;
    this.path = request;
  }

  public static Record startMeasurement(Path path) {
    return new Record(Instant.now(), path);
  }

  public void registerAttempt(Scmp.TimedMessage msg) {
    Attempt a = new Attempt(msg);
    if (remoteIP == null && a.state != Result.ResultState.LOCAL_AS) {
      remoteIP = msg.getPath().getRemoteAddress().getHostAddress();
    }
    attempts.add(a);
  }

  public void finishMeasurement(FileWriter fileWriter) {
    int nHops = PathRawParser.create(path.getRawPath()).getHopCount();
    StringBuilder out = new StringBuilder(ScionUtil.toStringIA(isdAs));
    out.append(",").append(remoteIP);
    out.append(",").append(time);
    out.append(",").append(nHops);
    out.append(",").append(ScionUtil.toStringPath(path.getRawPath()));
    for (Attempt a : attempts) {
      out.append(",").append(round(a.pingMs, 2));
    }
    out.append(System.lineSeparator());
    try {
      fileWriter.append(out.toString());
      fileWriter.flush();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public long getIsdAs() {
    return isdAs;
  }

  public void setICMP(String icmp) {
    this.icmp = icmp;
  }

  @Override
  public String toString() {
    StringBuilder out = new StringBuilder(ScionUtil.toStringIA(isdAs));
    out.append("   ").append(ScionUtil.toStringPath(path.getMetadata()));
    out.append("  ").append(remoteIP);
    for (Attempt a : attempts) {
      out.append(a);
    }
    return out + "  ICMP=" + icmp;
  }

  private static class Attempt {
    private double pingMs;
    private Result.ResultState state = Result.ResultState.NOT_DONE;

    Attempt(Scmp.TimedMessage msg) {
      if (msg == null) {
        state = Result.ResultState.LOCAL_AS;
        return;
      }
      if (msg.isTimedOut()) {
        state = Result.ResultState.TIME_OUT;
      } else {
        pingMs = msg.getNanoSeconds() / (double) 1_000_000;
        state = Result.ResultState.DONE;
      }
    }

    @Override
    public String toString() {
      return "  time=" + round(pingMs, 2) + "ms";
    }
  }
}