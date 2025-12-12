# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).

## [Unreleased]

-- Nothing yet

TODO
- Why two arguments for bestMessage and bestPath for Result? The path should be in the message.
- Report Worst paths!
- Create Result inside async traceroute, not outside.
- Publish
  - bugfix use getSequenceNumber i.o. getIdentifier
  - Use msg[i] to find best path
  - Use \t in summary output
- Investigate spurious 0-0:0 ASes in PingAll output


### Added

- Added command line `--help` and several options to multi-ping.
  [#20](https://github.com/netsec-ethz/scion-java-multiping/pull/20)
- Added output of global maxima (longest path, ...)
  [#24](https://github.com/netsec-ethz/scion-java-multiping/pull/24)
- Added output and config of local port 
  [#26](https://github.com/netsec-ethz/scion-java-multiping/pull/26)
- 
### Changed

- Output summary uses `\t` whitespaces for easier copying.
  [#21](https://github.com/netsec-ethz/scion-java-multiping/pull/21)
- Output uses more `\t` + drop ICMP out if disabled + print runtime
  [#23](https://github.com/netsec-ethz/scion-java-multiping/pull/23)

### Fixed

- PingAll did not report the best path.
  [#22](https://github.com/netsec-ethz/scion-java-multiping/pull/22)
- Adapted parser to new Assignment website layout.
  Distinguish ISD-AS vs unique AS.
  [#25](https://github.com/netsec-ethz/scion-java-multiping/pull/25)

## [0.5.0] - 2025-09-09

### Added

- Added async traceroute, stop SHIM, and use 30041
  [#14](https://github.com/netsec-ethz/scion-java-multiping/pull/14)
- Added output of median and average values
  [#15](https://github.com/netsec-ethz/scion-java-multiping/pull/15)
- Simple CI script to test formatting and JUnit
  [#16](https://github.com/netsec-ethz/scion-java-multiping/pull/16)

### Fixed

- ISD-AS assignment parser broken after website change
  [#13](https://github.com/netsec-ethz/scion-java-multiping/pull/13)
- Fixed calculation of average and median values
  [#17](https://github.com/netsec-ethz/scion-java-multiping/pull/17)
- Added proper tests, refactored PingAll and fixed minor issues
  [#18](https://github.com/netsec-ethz/scion-java-multiping/pull/18)

## [0.4.0] - 2025-04-04

### Added

- REPEAT setting in PingAll + some output cleanup
  [#9](https://github.com/netsec-ethz/scion-java-multiping/pull/9),
  [#10](https://github.com/netsec-ethz/scion-java-multiping/pull/10)

### Changed

- Post 0.3.0 release updates
  [#7](https://github.com/netsec-ethz/scion-java-multiping/pull/7)
- ICMP fixes (ignore IPv6 private addresses)
  [#8](https://github.com/netsec-ethz/scion-java-multiping/pull/8)
- Update to JPAN 0.5.1
  [#11](https://github.com/netsec-ethz/scion-java-multiping/pull/11)

## [0.3.0] - 2024-11-22

### Changed

- Changed JPAN dependency to 0.3.0.
  [#3](https://github.com/netsec-ethz/scion-java-multiping/pull/3)
- Regression: fixed pom.xml
  [#4](https://github.com/netsec-ethz/scion-java-multiping/pull/4)
- Bumped dependency on JPAN to 0.4.0
  [#5](https://github.com/netsec-ethz/scion-java-multiping/pull/5)
- Bumped dependency on JPAN to 0.4.1
  [#6](https://github.com/netsec-ethz/scion-java-multiping/pull/6)

## [0.2.0] - 2024-09-30

### Added

- Support `echo` requests in addition to traceroute.
  [#1](https://github.com/netsec-ethz/scion-java-multiping/pull/1)
- Added CHANGELOG
- EchoResponder

### Changed

- Changed pretty much all names and created an executable main class.
  [#2](https://github.com/netsec-ethz/scion-java-multiping/pull/2)

## [0.1.0] - 2024-09-16

### Added

- Everything

### Changed

- Nothing

### Fixed

- Nothing

### Removed

- Nothing

[Unreleased]: https://github.com/netsec-ethz/scion-java-multiping/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/netsec-ethz/scion-java-multiping/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/netsec-ethz/scion-java-multiping/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/netsec-ethz/scion-java-multiping/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/netsec-ethz/scion-java-multiping/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/netsec-ethz/scion-java-multiping/compare/init_root_commit...v0.1.0
