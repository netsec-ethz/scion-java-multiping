// Copyright 2025 ETH Zurich
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

import static org.scion.multiping.util.Util.println;
import static org.scion.multiping.util.Util.round;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import org.scion.jpan.ScionUtil;
import org.scion.jpan.Scmp;
import org.scion.jpan.internal.PathRawParser;

public class ResultSummary {

  private final List<Result> results = new ArrayList<>();
  private int nAsTried = 0;
  private int nAsSuccess = 0;
  private int nAsError = 0;
  private int nAsTimeout = 0;
  private int nAsNoPathFound = 0;

  private int nPathTried = 0;
  private int nPathSuccess = 0;
  private int nPathTimeout = 0;

  private int nSeenButNotListed = 0;
  private final List<String> seenButNotListed = new ArrayList<>();

  private long totalMaxHopsIsdAs;
  private int totalMaxHopsN;
  private long totalMaxPingIsdAs;
  private double totalMaxPingMs;
  private int totalMaxPathsN = 0;
  private long totalMaxPathsIsdAs = 0;

  public void incAsTried() {
    nAsTried++;
  }

  public void incAsSuccess() {
    nAsSuccess++;
  }

  public void incAsError() {
    nAsError++;
  }

  public void incAsTimeout() {
    nAsTimeout++;
  }

  public void incAsNoPathFound() {
    nAsNoPathFound++;
  }

  public void incPathTried() {
    nPathTried++;
  }

  public void incPathSuccess() {
    nPathSuccess++;
  }

  public void incPathTimeout() {
    nPathTimeout++;
  }

  public void incSeenButNotListed() {
    nSeenButNotListed++;
  }

  public void add(Result r) {
    results.add(r);
  }

  public Result getMaxPaths() {
    return max(r -> r.getPathCount() > 0, Comparator.comparingInt(Result::getPathCount));
  }

  public int getAsTimeouts() {
    return nAsTimeout;
  }

  public int getAsErrors() {
    return nAsError;
  }

  public int getPathTimeouts() {
    return nPathTimeout;
  }

  public void prettyPrint(Config config) {
    // max:
    Result maxPing = max(Result::isSuccess, (o1, o2) -> (int) (o1.getPingMs() - o2.getPingMs()));
    Result maxHops = max(r -> r.getHopCount() > 0, Comparator.comparingInt(Result::getHopCount));
    Result maxPaths = max(r -> r.getPathCount() > 0, Comparator.comparingInt(Result::getPathCount));

    // avg/median:
    double avgPing = avg(Result::isSuccess, Result::getPingMs);
    double avgHops = avg(r -> r.getHopCount() > 0, Result::getHopCount);
    double avgPaths = avg(r -> r.getPathCount() > 0, Result::getPathCount);
    double medianPing = median(Result::isSuccess, Result::getPingMs);
    double medianHops = median(r -> r.getHopCount() > 0, Result::getHopCount);
    double medianPaths = median(r -> r.getPathCount() > 0, Result::getPathCount);

    println("");
    println("Max hops            =\t " + maxHops.getHopCount() + "\t : " + maxHops);
    println("Max ping [ms]       =\t " + round(maxPing.getPingMs(), 2) + "\t : " + maxPing);
    println("Max paths           =\t " + maxPaths.getPathCount() + "\t : " + maxPaths);

    println("Total max hops      =\t " + totalMaxHopsN + "\t : " + ScionUtil.toStringIA(totalMaxHopsIsdAs));
    println("Total max ping [ms] =\t " + round(totalMaxPingMs, 2) + "\t : " + ScionUtil.toStringIA(totalMaxPingIsdAs));
    println("Total max paths     =\t " + totalMaxPathsN + "\t : " + ScionUtil.toStringIA(totalMaxPathsIsdAs));

    // Median:

    println("Median hops         =\t " + (int) medianHops);
    println("Median ping [ms]    =\t " + round(medianPing, 2));
    println("Median paths        =\t " + (int) medianPaths);

    println("Avg hops            =\t " + round(avgHops, 1));
    println("Avg ping [ms]       =\t " + round(avgPing, 2));
    println("Avg paths           =\t " + (int) round(avgPaths, 0));

    println("");
    println("AS Stats:");
    println(" all        =\t " + (nAsTried + 1)); // +1 for local AS
    println(" success    =\t " + nAsSuccess);
    println(" no path    =\t " + (nAsNoPathFound + 1)); // +1 for local AS
    println(" timeout    =\t " + nAsTimeout);
    println(" error      =\t " + nAsError);
    println(" not listed =\t " + nSeenButNotListed);
    println("Path Stats:");
    println(" all        =\t " + nPathTried);
    println(" success    =\t " + nPathSuccess);
    println(" timeout    =\t " + nPathTimeout);
    if (config.tryICMP) {
      println("ICMP Stats:");
      println(" all        =\t " + ICMP.nIcmpTried);
      println(" success    =\t " + ICMP.nIcmpSuccess);
      println(" timeout    =\t " + ICMP.nIcmpTimeout);
      println(" error      =\t " + ICMP.nIcmpError);
    }
  }

  private double avg(Predicate<Result> filter, ToDoubleFunction<Result> mapper) {
    return results.stream().filter(filter).mapToDouble(mapper).average().orElse(-1);
  }

  private Result max(Predicate<Result> filter, Comparator<Result> comparator) {
    return results.stream().filter(filter).max(comparator).orElse(Result.createDummy());
  }

  private <T> double median(Predicate<Result> filter, Function<Result, T> mapper) {
    List<T> list =
        results.stream().filter(filter).map(mapper).sorted().collect(Collectors.toList());
    if (list.isEmpty()) {
      return -1;
    }
    if (list.get(0) instanceof Double) {
      return (Double) list.get(list.size() / 2);
    }
    return (Integer) list.get(list.size() / 2);
  }

  public void checkTotalMax(long remote, int size) {
    if (size > totalMaxPathsN) {
      totalMaxPathsN = size;
      totalMaxPathsIsdAs = remote;
    }
  }

  public void checkTotalMax(long isdAs, Scmp.TimedMessage msg) {
    int nHops = PathRawParser.create(msg.getPath().getRawPath()).getHopCount();
    if (nHops > totalMaxHopsN) {
      totalMaxHopsN = nHops;
      totalMaxHopsIsdAs = isdAs;
    }

    if (!msg.isTimedOut()) {
      long milliSeconds = msg.getNanoSeconds() / 1_000_000;
      if (milliSeconds > totalMaxPingMs) {
        totalMaxPingMs = milliSeconds;
        totalMaxPingIsdAs = isdAs;
      }
    }
  }
}
