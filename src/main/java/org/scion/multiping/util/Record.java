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
  private boolean isEcho;
  private final int attemptRepeatCount;
  private State state = State.SUCCESS;

  public Record(Instant time, Path request, long isdAs, int attemptRepeatCount) {
    this.isdAs = isdAs;
    this.time = time;
    this.path = request;
    this.attemptRepeatCount = attemptRepeatCount;
  }

  public static Record startMeasurement(Path path, int attemptRepeatCount) {
    return new Record(Instant.now(), path, path.getRemoteIsdAs(), attemptRepeatCount);
  }

  public static Record createNoPathRecord(long isdAs, FileWriter fileWriter) {
    Record rec = new Record(Instant.now(), null, isdAs, 0);
    rec.setState(State.NO_PATH);
    rec.finishMeasurement(fileWriter);
    return rec;
  }

  public static Record createErrorRecord(long isdAs, FileWriter fileWriter) {
    Record rec = new Record(Instant.now(), null, isdAs, 0);
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

  public Attempt registerAttempt(Attempt.State attemptState) {
    Attempt a = new Attempt(attemptState);
    attempts.add(a);
    if (attemptState != Attempt.State.SUCCESS && state == State.SUCCESS) {
      state = State.ERROR;
    }
    return a;
  }

  public void finishMeasurement(FileWriter fileWriter) {
    summarizeState();
    int nHops = path == null ? 0 : PathRawParser.create(path.getRawPath()).getHopCount();
    StringBuilder out = new StringBuilder(ScionUtil.toStringIA(isdAs));
    out.append(",").append(remoteIP == null ? "" : remoteIP);
    out.append(",").append(time);
    out.append(",").append(isEcho ? "ECHO" : "TRACE");
    out.append(",").append(state.name());
    out.append(",").append(nHops);
    out.append(",").append(path == null ? "[]" : ScionUtil.toStringPath(path.getMetadata()));
    for (Attempt a : attempts) {
      if (a.state == Attempt.State.SUCCESS) {
        out.append(",").append(round(a.pingMs, 2));
      } else {
        out.append(",").append(a.state.name());
      }
    }
    out.append(System.lineSeparator());
    try {
      fileWriter.append(out.toString());
      fileWriter.flush();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private State summarizeState() {
    for (Attempt a : attempts) {
      if (a.state != Attempt.State.SUCCESS) {
        this.state = State.ERROR;
        return this.state;
      }
    }
    if (attempts.size() < attemptRepeatCount) {
      this.state = State.ERROR;
      return State.ERROR;
    }
    return this.state;
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

  public void isEcho(boolean b) {
    this.isEcho = b;
  }

  public boolean isEcho() {
    return isEcho;
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
      ERROR_SEQID,
      ERROR_PATH,
    }

    private double pingMs;
    private final State state;

    Attempt(State state) {
      this.state = state;
    }

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

    public State getState() {
      return state;
    }

    @Override
    public String toString() {
      return "  time=" + round(pingMs, 2) + "ms";
    }
  }
}
