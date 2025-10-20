# Mats<sup>3</sup> Development

This README describes the layout of the MatsBrokerMonitor project, and the development environment, and how to build and
publish.

# Modules / Jars

### API:
* `matsbrokermonitor-api`: The MatsBrokerMonitor API, which consist of two classes (and a utility):
  `MatsBrokerMonitor` is the broker-specific monitor class which fetches what queues exist (including DLQs), queue
  lengths, age of head message, and broker info. `MatsBrokerBrowseAndActions` provides functions for browsing a
  queue; and browsing, reissuing and deleting messages, all functions that can be implemented directly on the JMS
  API.

### Implementation:
* `matsbrokermonitor-activemq`: Implements the `MatsBrokerMonitor` API class for ActiveMQ.
* `matsbrokermonitor-jms`: Implements the `MatsBrokerBrowseAndActions` API class for JMS.

### HTML GUI
**This is basically the main point of this project!**

* `matsbrokermonitor-htmlgui`: A rich, efficient and feature packed, embeddable HTML GUI for viewing the broker state,
  with all its queues and DLQs, and also for browsing, reissuing and deleting messages, or "muting" them (i.e. not
  alerting on the DLQ anymore). To instantiate it, you need one instance each of `MatsBrokerMonitor` and
  `MatsBrokerBrowseAndActions`.

This GUI is meant to be embedded in a 'main monitor' type application which one hopes that most microservice architected
applications will have. It is extremely simple to use.

### Broadcast and Healthcheck

* `matsbrokermonitor-broadcastandcontrol`: Hooks onto the events emitted by MatsBrokerMonitor, and broadcasts 
  a condensed, single-message variant of the broker's total state to all MatsFactories on the Mats Fabric.
  The "control" part refers to it also accepting certain commands from the services, specifically a way to remotely
  trigger a full update of the the broker state (otherwise you'd have to go to the MBM HTML GUI to do it).
* `matsbrokermonitor-broadcastreceiver`: Installed on each microservice's MatsFactory. Reads the broadcast sent by the
  previous module, and updates its own MatsFactory's instance with the subset of information that concerns its own
  Endpoints and Stages.
* `matsbrokermonitor-stbhealthcheck`: "Storebrand HealtCheck" is a system letting a microservice tell the world how
  it fares, alike the "actuator" in Spring, only richer. This module reads the data that the previous module installed
  on the MatsFactory, and alerts if there are any DLQs, or if the head message of a queue is old, implying that the
  stage is not processing messages fast enough.

## Gradle tasks:

Note the property `mats.build.java_version` which can be set on the Gradle invocation using
`-Pmats.build.java_version={version}` to override the default Java 11 version. This is used by the matrix build in
GitHub Actions. Note that the command line Java version must match this - Gradle's "build tooling" is currently not
used.

* `build`: Build code, jar files, javadoc, and runs tests.
* `build -x test`: build, skipping tests.
* `systemInformation`: Shows Java Properties, System Environment, and versions of Java, Gradle and Groovy. The Java
  version shown here is also the one used for building.
* `allDeps`: Runs the `DependencyReportTask` for the Java projects (i.e., get all library/"maven" dependencies for all
  configurations)
* `clean`: Clean the built artifacts.

## Development

The `matsbrokermonitor-activemq` has some tests. However, due to the visual nature of this project's main goal, i.e. the
HTML GUI, most development has been done using the test server `MatsBrokerMonitor_TestJettyServer`,
[located here](matsbrokermonitor-htmlgui/src/test/java/io/mats3/matsbrokermonitor/htmlgui/MatsBrokerMonitor_TestJettyServer.java).
Find it, right-click run, and you should get a small menu at [http://localhost:8080/](http://localhost:8080/), with
the HTML GUI located at [/matsbrokermonitor](http://localhost:8080/matsbrokermonitor/). Notice that it also displays
the `mats-localinspect` embeddable GUI from Mats<sup>3</sup> proper, for its own MatsFactory.

## Testing

As mentioned in Development, there aren't that many tests. Most development and testing is done manually and visually
using the test server.

## Publishing

**NOTE: First update the version in root `build.gradle`, `ActiveMqMatsBrokerMonitor.java` and
`JmsMatsBrokerBrowseAndActions`, read below for format per release type!**

**Remember to git commit and tag the version bump before publishing, read below for git tag and message format!**

For Java publishing, we use Gradle and the
[Vanniktech maven.publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/), which uploads via
the Portal Publisher API.

To see what will be published, we rely on the Central Portal's "review" functionality.

Release types / SemVer tags:
* Experimental (testing a new feature / fix):
    * Prefix `EXP-`, suffix: `.EXPX+<iso date>` to the version, X being a counter from 0.  
      example: `EXP-1.0.0.EXP0+2025-10-16`
* Release Candidate (before a new version, testing that it works, preferably in production!):
    * Prefix `RC-`, suffix `.RCX+<iso date>` to the version, X being a counter from 0  
      example: `RC-1.0.0.RC0+2025-10-16`
* Release
    * Suffix `+<iso date>` to the version.  
      example: `1.0.0+2025-10-16`

### Transcript of a successful RC publish:

#### Change version number and build:

Change version in `build.gradle`, `ActiveMqMatsBrokerMonitor.java` and `JmsMatsBrokerBrowseAndActions` to relevant
(RC) version! Read above on the version string format.

Build and test:
```bash
$ ./gradlew clean build
```

#### Commit and tag git:

Commit the version bump (both package.json and MatsSocket.js), message shall read ala: _(Note "Candidate" in the message:
Remove it if not!)_
`Release Candidate: RC-1.0.0.RC5+2025-05-20  (from RC-1.0.0.RC4+2025-05-15)`

Tag git, and push, and push tags. _(Note the "Candidate" in the message: Remove it if not!)_
```shell
$ git tag -a vRC-1.0.0.RC5+2025-05-20 -m "Release Candidate vRC-1.0.0.RC5+2025-05-20"
$ git push && git push --tags
```

#### Publish to Maven Central Repository:

```shell
$ ./gradlew publishToMavenCentral
```

Afterwards, log in to [Maven Central Repository Portal](https://central.sonatype.com/publishing/deployments), find the
newly published version.

Check over it, and if everything looks good, ship it!

#### Verify publication

It says "Publishing" for quite a while in the Portal. Afterwards, it should say "Published". It might still take some
time to appear in all places.

Eventually, the new version should be available in:
* [Maven Central Repository](https://central.sonatype.com/namespace/io.mats3.matsbrokermonitor) - First place it
  appears, directly after "Published" on Portal. Same style GUI as Portal.
* [repo.maven.apache.org/maven2](https://repo.maven.apache.org/maven2/io/mats3/matsbrokermonitor/) - HTML File browser.
  This is where Maven/Gradle actually downloads artifacts from, so when they're available here, you can update your
  projects.
* [MVN Repository](https://mvnrepository.com/artifact/io.mats3.matsbrokermonitor) - good old MVN Repository. Often VERY
  slow to display the new version.
