# Publishing to Maven

Get an account on Sonatype JIRA: https://issues.sonatype.org/secure/Signup!default.jspa

Tell your username to Jeff, who will file a ticket to add you
to have access to publish.

Create a `~/.m2/settings.xml` file with the following in it:
```xml
<settings>
  <servers>
    <server>
      <id>ossrh</id>
      <username>FILL THIS IN</username>
      <password>FILL THIS IN</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>ossrh</id>
      <activation>
        <activeByDefault>true</activeByDefault>
      </activation>
      <properties>
        <gpg.executable>gpg2</gpg.executable>
        <gpg.passphrase>FILL THIS IN</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
</settings>
```

Edit `pom.xml`, set the version number, commit it to GitHub,
use `git pull` to get your master to the new commit and then
make/push a tag (`git tag v2.0.1; git push --tags`)`

Type `mvn deploy`.

At this point, the release is in a state called "closed", but it will
not be synced to repo1.maven.org until it is "released". Your local
~/.m2/repository/ch/epfl directory has the signed JAR in it. Use
a demo app to try to compile against it to make sure it works as
expected.

Type `mvn nexus-staging:release` to release the artifact to Maven.