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
  /**
   * IMPORTANT: States are ordered by precedence! Lower-ordinal states override higher-ordinal
   * states. For example, an attempt with NOT_DONE means that the whole record is NOT_DONE. The
   * ordering doesn't make sense for every possible combination, but it is important that NOT_DONE
   * overrides everything else and that SUCCESS is overridden by everything else.
   */
  public enum State {
    ERROR,
    NO_PATH,
    LOCAL_AS,
    SUCCESS,
  }

  private final long isdAs;
  private final ArrayList<Attempt> attempts = new ArrayList<>();
  private final Instant time;
  private final Path path;
  private String remoteIP;
  private String icmp;
  private State state = State.SUCCESS;

  public Record(Instant time, Path request, long isdAs) {
    this.isdAs = isdAs;
    this.time = time;
    this.path = request;
  }

  public static Record startMeasurement(Path path) {
    return new Record(Instant.now(), path, path.getRemoteIsdAs());
  }

  public static Record createNoPathRecord(long isdAs, FileWriter fileWriter) {
    Record rec = new Record(Instant.now(), null, isdAs);
    rec.setState(State.NO_PATH);
    rec.finishMeasurement(fileWriter);
    return rec;
  }

  public static Record createErrorRecord(long isdAs, FileWriter fileWriter) {
    Record rec = new Record(Instant.now(), null, isdAs);
    rec.setState(State.ERROR);
    rec.finishMeasurement(fileWriter);
    return rec;
  }

  public Attempt registerAttempt(Scmp.TimedMessage msg) {
    Attempt a = new Attempt(msg);
    if (remoteIP == null) {
      remoteIP = msg.getPath().getRemoteAddress().getHostAddress();
    }
    attempts.add(a);
    return a;
  }

  public void finishMeasurement(FileWriter fileWriter) {
    int nHops = path == null ? 0 : PathRawParser.create(path.getRawPath()).getHopCount();
    StringBuilder out = new StringBuilder(ScionUtil.toStringIA(isdAs));
    out.append(",").append(remoteIP == null ? "" : remoteIP);
    out.append(",").append(time);
    out.append(",").append(state.name());
    out.append(",").append(nHops);
    out.append(",").append(path == null ? "[]" : ScionUtil.toStringPath(path.getRawPath()));
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

  public void setState(State state) {
    this.state = state;
  }

  public long getIsdAs() {
    return isdAs;
  }

  public void setICMP(String icmp) {
    this.icmp = icmp;
  }

  public Path getPath() {
    return path;
  }

  public String getRemoteIP() {
    return remoteIP;
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

  public static class Attempt {
    public enum State {
      SUCCESS,
      TIMEOUT,
    }

    private double pingMs;
    private final State state;

    Attempt(Scmp.TimedMessage msg) {
      if (msg.isTimedOut()) {
        state = State.TIMEOUT;
      } else {
        pingMs = msg.getNanoSeconds() / (double) 1_000_000;
        state = State.SUCCESS;
      }
    }

    public double getPingMs() {
      return pingMs;
    }

    @Override
    public String toString() {
      return "  time=" + round(pingMs, 2) + "ms";
    }
  }
}
