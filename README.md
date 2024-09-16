# SCION Java MultiPing

A tool that allows pinging / trace route) all known ASes in one run.

MultiPing provides several tools:

* `DownloadAssignmentList` for downloading a list of known ISD/AS assignment
* `EchoAll` for sending a single traceroute to all known ASes along the shortest path (default behaviour)
* `EchoRepeat` for repeatedly probing (traceroute) multiple paths to multiple ASes.

# `DownloadAssignmentList`

The tool parses [Anapayas ISD/AS assignment website](https://docs.anapaya.net/en/latest/resources/isd-as-assignments/)
and writes the result to a local file 'EchoRepeatDestinations-new.csv'.

# `EchoAll`

The tool parses [Anapayas ISD/AS assignment website](https://docs.anapaya.net/en/latest/resources/isd-as-assignments/),
identifies the shortest path to each AS and sends a traceroute to each AS.
It reports the number of paths to each AS as well as the shortest path with latency, remote IP, hop count and remote IP.

It also provides a summary of its findings.

# `EchoRepeat`

The tool reads a list if ISD/AS codes from a csv file (`isdAsFile`), repeatedly sends traceroute
SCMP requests to each AS and writes the results to an output file (`outputFile`).

SCMP request are sent in several rounds, each round consisting of several attempts.
In the default configuration (see below) it will:

* execute 144 rounds (`roundRepeatCnt`)
* start a new round every 600s = 10 minutes (`roundDelaySec`)
* in each round, select the 20 shortest paths (`maxPathsPerDestination`) for each AS
* in each round, execute 5 "attempts" (`attemptRepeatCnt`)
* in each "attempt", send a single traceroute request along every selected path to every AS,
  then wait 100ms (`attemptDelayMs`) before executing the next attempt

144 rounds á 10 minutes results in a total runtime of about 24h.

## Configuration

The tool uses a configuration file `EchoRepeatConfig.json` that can contain the following arguments:

```json
{
  "attemptRepeatCnt": 5,
  "attemptDelayMs": 100,
  "roundRepeatCnt": 144,
  "roundDelaySec": 600,
  "maxPathsPerDestination": 20,
  "tryICMP": false,
  "isdAsFile": "EchoRepeatDestinations-short.csv",
  "outputFile": "EchoOutput.csv",
  "localPort": 30041,
  "consoleOutput": true
}
```

## Execution

To run, the tool requires a configuration file (see above or [here](/EchoRepeatConfig.json)) and an input file
(see [here](/EchoRepeatDestinations-short.csv)).

An executable jar file is available in
the [GitHub Releases section](https://github.com/netsec-ethz/scion-java-multiping/releases/download/v0.1.0/scion-multiping-0.1.0-shaded.jar).
It can be executed
with:

```dtd
java -jar scion-multiping-0.1.0-shaded.jar
```

See also the troubleshooting section below in case of issues.

## Output

The output file is a csv file with one row per round/path.
Each row consists of:

* ISD/AS
* Remote IP (if known)
* Time stamp
* Result: can be SUCCESS, NO_PATH (no path found to destination), LOCAL_AS (the destination AS
  is the local AS) or ERROR
* Hop count of the path taken
* The path
* millisecond latency for each attempt (default: 5)

For example, this shows measurements for three paths to `64-0:0:ce7`:

```csv
64-0:0:ce7,193.247.172.126,2024-09-13T15:46:04.044713600Z,SUCCESS,2,[2>6 19>9],14.54,14.38,13.86,13.23,14.86
64-0:0:ce7,193.247.170.170,2024-09-13T15:46:04.045213500Z,SUCCESS,2,[1>5 17>1],10.4,10.3,9.79,9.91,10.76
64-0:0:ce7,193.247.170.170,2024-09-13T15:46:04.045213500Z,SUCCESS,2,[2>6 17>1],10.7,10.3,9.98,9.86,10.75
```

This shows a LOCAL_AS and a NO_PATH event:

```
64-2:0:9,,2024-09-13T15:46:05.979346700Z,LOCAL_AS,0,[]
71-2:0:4a,,2024-09-13T15:46:16.554705200Z,NO_PATH,0,[]
```

# Troubleshooting

## No DNS search domain found. Please check your /etc/resolv.conf or similar.

This happens, for example, on Windows when using a VPN. One solution is to execute the jar with the following property (
the example works only for `ethz.ch`):

```
java -DSCION_DNS_SEARCH_DOMAINS=ethz.ch.  -jar target/scion-multiping-0.0.1-ALPHA-SNAPSHOT-shaded.jar
```