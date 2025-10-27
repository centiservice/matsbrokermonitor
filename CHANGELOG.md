## B-2.0.0.B0+2025-10-27

Notice: MatsBrokerMonitor Beta 2.0 series will require Mats<sup>3</sup> 2.0 too, which as of writing also is
in Beta.

* New major version, due to Java 21 and Jakarta namespaces.
* **Moved over to jakarta-namespace for all javax libraries, most notably JMS.**
* **V2-series will require Java 21.**
* _Beta-temp: Depending on Mats<sup>3</sup> version `B-2.0.0.B0+2025-10-22`_
* Upgraded to Gradle 9.1.0. Finally, no "Deprecated Gradle features were used in this build..."!
* All dependencies upgraded, now past the Java 17+ requiring libs.
* Core (Mats<sup>3</sup> implementation):
    * JMS 3.1.0
    * Jackson 3.0.0
* For testing:
    * ActiveMQ 6.1.7
    * Jetty 12.1.2

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