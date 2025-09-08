# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Added async traceroute, stop SHIM, and use 30041
  [#14](https://github.com/netsec-ethz/scion-java-multiping/pull/14)

### Fixed

- ISD-AS assignment parser broken after website change
  [#13](https://github.com/netsec-ethz/scion-java-multiping/pull/13)

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

[Unreleased]: https://github.com/netsec-ethz/scion-java-multiping/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/netsec-ethz/scion-java-multiping/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/netsec-ethz/scion-java-multiping/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/netsec-ethz/scion-java-multiping/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/netsec-ethz/scion-java-multiping/compare/init_root_commit...v0.1.0
