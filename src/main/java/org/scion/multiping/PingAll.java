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

package org.scion.multiping;

import static org.scion.multiping.util.Util.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import org.scion.jpan.*;
import org.scion.jpan.internal.PathRawParser;
import org.scion.multiping.util.*;

/**
 * This program takes a list of ISD/AS addresses and tries to measure latency to all of them. It
 * will also attempt an ICMP ping for comparison.<br>
 * The list is derived from <a
 * href="https://docs.anapaya.net/en/latest/resources/isd-as-assignments/">here</a> and is locally
 * stored in ISD-AS-Assignments.csv, see {@link ParseAssignments}.java.
 *
 * <p>There are several options for executing measurements (see "Policy"):<br>
 * - SCMP traceroute vs SCMP echo<br>
 * - Fastest vs shortest
 *
 * <p>Shortest: Report results on the path with the fewest hops. The number of hops can be evaluated
 * locally, so this is very fast.
 *
 * <p>Fastest: Report the path with the lowest latency. This takes much longer because it will try
 * all available paths before it can report on the best path.
 */
public class PingAll {
  private static final int REPEAT = 3;
  private static final boolean SHOW_ONLY_ICMP = false;
  private static final Config config = new Config();

  private static final int localPort = 30041;
  private static final boolean STOP_SHIM = true;

  static {
    config.tryICMP = false;
    if (SHOW_ONLY_ICMP) {
      DELAYED_PRINT = true;
    }
  }

  private int nAsTried = 0;
  private int nAsSuccess = 0;
  private int nAsError = 0;
  private int nAsTimeout = 0;
  private int nAsNoPathFound = 0;

  private int nPathTried = 0;
  private int nPathSuccess = 0;
  private int nPathTimeout = 0;

  private static final Set<Long> listedAs = new HashSet<>();
  private static final Set<Long> seenAs = new HashSet<>();
  private static final List<Result> results = new ArrayList<>();

  private enum Policy {
    /** Fastest path using SCMP traceroute */
    FASTEST_TR,
    /** Fastest path using SCMP async traceroute */
    FASTEST_ECHO,
    FASTEST_TR_ASYNC,
    /** Shortest path using SCMP traceroute */
    SHORTEST_TR,
    /** Fastest path using SCMP echo */
    SHORTEST_ECHO
  }

  private static final Policy POLICY = Policy.FASTEST_TR_ASYNC;
  private static final boolean SHOW_PATH = false;

  public static void main(String[] args) throws IOException {
    PRINT = true;
    // System.setProperty(Constants.PROPERTY_DNS_SEARCH_DOMAINS, "ethz.ch.");
    System.setProperty(Constants.PROPERTY_SHIM, STOP_SHIM ? "false" : "true"); // disable SHIM

    println("Settings:");
    println("  Path policy = " + POLICY);
    println("  ICMP=" + config.tryICMP);
    println("  printOnlyICMP=" + SHOW_ONLY_ICMP);

    PingAll demo = new PingAll();
    List<ParseAssignments.HostEntry> allASes = DownloadAssignmentsFromWeb.getList();
    // remove entry for local AS
    long localAS = Scion.defaultService().getLocalIsdAs();
    allASes = allASes.stream().filter(e -> e.getIsdAs() != localAS).collect(Collectors.toList());
    // Process all ASes
    for (ParseAssignments.HostEntry e : allASes) {
      print(ScionUtil.toStringIA(e.getIsdAs()) + " \"" + e.getName() + "\"  ");
      demo.runDemo(e);
      listedAs.add(e.getIsdAs());
    }

    // Try to identify ASes that occur in any paths but that are not on the public list.
    int nSeenButNotListed = 0;
    for (Long isdAs : seenAs) {
      if (!listedAs.contains(isdAs)) {
        nSeenButNotListed++;
      }
    }

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
    println("Max hops         = " + maxHops.getHopCount() + ":    " + maxHops);
    println("Max ping [ms]    = " + round(maxPing.getPingMs(), 2) + ":    " + maxPing);
    println("Max paths        = " + maxPaths.getPathCount() + ":    " + maxPaths);

    println("Median hops      = " + (int) medianHops);
    println("Median ping [ms] = " + round(medianPing, 2));
    println("Median paths     = " + (int) medianPaths);

    println("Avg hops         = " + round(avgHops, 1));
    println("Avg ping [ms]    = " + round(avgPing, 2));
    println("Avg paths        = " + (int) round(avgPaths, 0));

    println("");
    println("AS Stats:");
    println(" all        = " + demo.nAsTried);
    println(" success    = " + demo.nAsSuccess);
    println(" no path    = " + demo.nAsNoPathFound);
    println(" timeout    = " + demo.nAsTimeout);
    println(" error      = " + demo.nAsError);
    println(" not listed = " + nSeenButNotListed);
    println("Path Stats:");
    println(" all        = " + demo.nPathTried);
    println(" success    = " + demo.nPathSuccess);
    println(" timeout    = " + demo.nPathTimeout);
    println("ICMP Stats:");
    println(" all        = " + ICMP.nIcmpTried);
    println(" success    = " + ICMP.nIcmpSuccess);
    println(" timeout    = " + ICMP.nIcmpTimeout);
    println(" error      = " + ICMP.nIcmpError);
  }

  private void runDemo(ParseAssignments.HostEntry remote) throws IOException {
    nAsTried++;
    ScionService service = Scion.defaultService();
    // Dummy address. The traceroute will contact the control service IP instead.
    InetSocketAddress destinationAddress =
        new InetSocketAddress(InetAddress.getByAddress(new byte[] {0, 0, 0, 0}), 30041);
    int nPaths;
    Scmp.TimedMessage[] msg = new Scmp.TimedMessage[REPEAT];
    Ref<Path> bestPath = Ref.empty();
    try {
      List<Path> paths = service.getPaths(remote.getIsdAs(), destinationAddress);
      if (paths.isEmpty()) {
        String src = ScionUtil.toStringIA(service.getLocalIsdAs());
        String dst = ScionUtil.toStringIA(remote.getIsdAs());
        if (!SHOW_ONLY_ICMP) {
          println("WARNING: No path found from " + src + " to " + dst);
        }
        nAsNoPathFound++;
        results.add(new Result(remote, Result.State.NO_PATH));
        return;
      }
      nPaths = paths.size();
      msg[0] = findPaths(paths, bestPath);
      if (msg[0] != null && REPEAT > 1) {
        try (ScmpSender sender = Scmp.newSenderBuilder().setLocalPort(localPort).build()) {
          for (int i = 1; i < msg.length; i++) {
            List<Scmp.TracerouteMessage> messages = sender.sendTracerouteRequest(bestPath.get());
            msg[i] = messages.get(messages.size() - 1);
          }
        }
      }
    } catch (ScionRuntimeException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      results.add(new Result(remote, Result.State.ERROR));
      return;
    }
    Result result = new Result(remote, msg[0], bestPath.get(), nPaths);
    results.add(result);

    if (msg[0] == null) {
      return;
    }

    // ICMP ping
    StringBuilder icmpMs = new StringBuilder();
    for (int i = 0; i < REPEAT; i++) {
      String icmpMsStr = ICMP.pingICMP(msg[0].getPath().getRemoteAddress(), config);
      icmpMs.append(icmpMsStr).append(" ");
      if (icmpMsStr.startsWith("TIMEOUT") || icmpMsStr.startsWith("N/A")) {
        break;
      }
    }
    result.setICMP(icmpMs.toString());

    // output
    int nHops = PathRawParser.create(msg[0].getPath().getRawPath()).getHopCount();
    String addr = msg[0].getPath().getRemoteAddress().getHostAddress();
    print(addr + "  nPaths=" + nPaths + "  nHops=" + nHops + "  time= ");
    for (Scmp.TimedMessage m : msg) {
      double millis = round(m.getNanoSeconds() / (double) 1_000_000, 2);
      print(millis + "ms ");
    }
    String icmpStr = icmpMs.toString();
    print("  ICMP= " + icmpStr);
    if (SHOW_PATH) {
      print("  " + ScionUtil.toStringPath(bestPath.get().getMetadata()));
    }
    if (SHOW_ONLY_ICMP && (icmpStr.startsWith("N/A") || icmpStr.startsWith("TIMEOUT"))) {
      clearPrintQueue();
    } else {
      println();
    }
    if (msg[0].isTimedOut()) {
      nAsTimeout++;
    } else {
      nAsSuccess++;
    }
  }

  private Scmp.TimedMessage findPaths(List<Path> paths, Ref<Path> bestOut) {
    switch (POLICY) {
      case FASTEST_TR:
        return findFastestTR(paths, bestOut);
      case FASTEST_TR_ASYNC:
        return findFastestTRasync(paths, bestOut);
      case SHORTEST_TR:
        return findShortestTR(paths, bestOut);
      case SHORTEST_ECHO:
        return findShortestEcho(paths, bestOut);
      default:
        throw new UnsupportedOperationException();
    }
  }

  private Scmp.EchoMessage findShortestEcho(List<Path> paths, Ref<Path> refBest) {
    Path path = PathPolicy.MIN_HOPS.filter(paths).get(0);
    refBest.set(path);
    ByteBuffer bb = ByteBuffer.allocate(0);
    try (ScmpSender sender = Scmp.newSenderBuilder().setLocalPort(localPort).build()) {
      nPathTried++;
      Scmp.EchoMessage msg = sender.sendEchoRequest(path, bb);
      if (msg == null) {
        println(" -> local AS, no timing available");
        nPathSuccess++;
        nAsSuccess++;
        return null;
      }

      if (msg.isTimedOut()) {
        nPathTimeout++;
        return msg;
      }

      nPathSuccess++;
      return msg;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      return null;
    }
  }

  private Scmp.TracerouteMessage findShortestTR(List<Path> paths, Ref<Path> refBest) {
    Path path = PathPolicy.MIN_HOPS.filter(paths).get(0);
    refBest.set(path);
    try (ScmpSender sender = Scmp.newSenderBuilder().setLocalPort(localPort).build()) {
      nPathTried++;
      List<Scmp.TracerouteMessage> messages = sender.sendTracerouteRequest(path);
      if (messages.isEmpty()) {
        println(" -> local AS, no timing available");
        nPathSuccess++;
        nAsSuccess++;
        return null;
      }

      for (Scmp.TracerouteMessage msg : messages) {
        seenAs.add(msg.getIsdAs());
      }

      Scmp.TracerouteMessage msg = messages.get(messages.size() - 1);
      if (msg.isTimedOut()) {
        nPathTimeout++;
        return msg;
      }

      nPathSuccess++;
      return msg;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      return null;
    }
  }

  private Scmp.TracerouteMessage findFastestTR(List<Path> paths, Ref<Path> refBest) {
    Scmp.TracerouteMessage best = null;
    try (ScmpSender sender = Scmp.newSenderBuilder().setLocalPort(localPort).build()) {
      for (Path path : paths) {
        nPathTried++;
        List<Scmp.TracerouteMessage> messages = sender.sendTracerouteRequest(path);
        if (messages.isEmpty()) {
          println(" -> local AS, no timing available");
          nPathSuccess++;
          nAsSuccess++;
          return null;
        }

        for (Scmp.TracerouteMessage msg : messages) {
          seenAs.add(msg.getIsdAs());
        }

        Scmp.TracerouteMessage msg = messages.get(messages.size() - 1);
        if (msg.isTimedOut()) {
          nPathTimeout++;
          return msg;
        }

        nPathSuccess++;

        if (best == null || msg.getNanoSeconds() < best.getNanoSeconds()) {
          best = msg;
          refBest.set(path);
        }
      }
      return best;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      return null;
    }
  }

  private Scmp.TracerouteMessage findFastestTRasync(List<Path> paths, Ref<Path> refBest) {
    ConcurrentHashMap<Integer, Scmp.TimedMessage> messages = new ConcurrentHashMap<>();
    CountDownLatch barrier = new CountDownLatch(paths.size());
    ScmpSenderAsync.ResponseHandler handler =
        new ScmpSenderAsync.ResponseHandler() {
          @Override
          public void onResponse(Scmp.TimedMessage msg) {
            barrier.countDown();
            messages.put(msg.getIdentifier(), msg);
          }

          @Override
          public void onTimeout(Scmp.TimedMessage msg) {
            barrier.countDown();
            messages.put(msg.getIdentifier(), msg);
          }

          @Override
          public void onError(Scmp.ErrorMessage msg) {
            barrier.countDown();
          }

          @Override
          public void onException(Throwable t) {
            barrier.countDown();
          }
        };

    Scmp.TracerouteMessage best = null;
    try (ScmpSenderAsync sender =
        Scmp.newSenderAsyncBuilder(handler).setLocalPort(localPort).build()) {
      for (Path path : paths) {
        nPathTried++;
        int id = sender.sendTracerouteLast(path);
        if (id == -1) {
          println(" -> local AS, no timing available");
          nPathSuccess++;
          nAsSuccess++;
          return null;
        }
      }

      // Wait for all messages to be received
      try {
        if (!barrier.await(1100, TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException(
              "Missing messages: " + barrier.getCount() + "/" + paths.size());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }

    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      return null;
    }

    for (Scmp.TimedMessage tm : messages.values()) {
      Scmp.TracerouteMessage msg = (Scmp.TracerouteMessage) tm;
      seenAs.add(msg.getIsdAs());

      if (msg.isTimedOut()) {
        nPathTimeout++;
        if (best == null) {
          best = msg;
        }
        continue;
      }

      nPathSuccess++;

      if (best == null || msg.getNanoSeconds() < best.getNanoSeconds()) {
        best = msg;
        refBest.set(msg.getRequest().getPath());
      }
    }
    return best;
  }

  private static double avg(Predicate<Result> filter, ToDoubleFunction<Result> mapper) {
    return results.stream().filter(filter).mapToDouble(mapper).average().orElse(-1);
  }

  private static Result max(Predicate<Result> filter, Comparator<Result> comparator) {
    return results.stream().filter(filter).max(comparator).orElseThrow(NoSuchElementException::new);
  }

  private static <T> double median(Predicate<Result> filter, Function<Result, T> mapper) {
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
}
