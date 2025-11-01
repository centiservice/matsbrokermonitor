## 2.0.0+2025-11-01

* New major version, due to Java 21 and Jakarta namespaces.
* **Moved over to jakarta-namespace for all javax libraries, most notably JMS.**
* **V2-series will require Java 21.**
* All dependencies upgraded. Both wrt. the jakarta-change, and past Java 17-requiring libs.
* Core:
  * Jakarta JMS 3.1.0
  * Jackson 3.0.1
  * SLF4J 2.0.17
* HealthCheck:
  * Storebrand HealthCheck 0.4.1+2024-05-07
* "Dev dependencies":
  * ActiveMQ 6.1.8
  * Jetty 12.1.3, w/ _ee11_, Jakarta Servlet 6.1.0
  * Logback 1.5.20
* Upgraded to Gradle 9.2.0. Finally, no "Deprecated Gradle features were used in this build..."!

## 1.1.1+2025-10-20

* Changed to use Maven Central Portal API for publish, using Vanniktech's plugin.
* Added README-development.md
* Upgraded all dependencies, incl. Mats<sup>3</sup> to 1.0.1+2025-10-20.
* Started Changelog.

## 1.1.0+2025-06-20

* Updated to 1.0.0 series of Mats<sup>3</sup>.

_.. 13 x 1.0.x releases .._

## 1.0.0-2024-04-05

* first 1.0.0 release.