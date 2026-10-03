# Publish to Maven Central (Central Portal)

Publishing goes through the Central Portal (https://central.sonatype.com); the
legacy OSSRH / nexus-staging flow is retired. The `release` profile in
`pom.xml` is already configured with `central-publishing-maven-plugin`.

## One-time setup

1. Generate a GPG key (skip if you already have one) and publish it:

    ```shell
    export GPG_TTY=$(tty)
    gpg --gen-key
    gpg --list-keys          # note the key fingerprint
    gpg --keyserver hkp://keyserver.ubuntu.com --send-keys <FINGERPRINT>
    gpg --keyserver hkps://keys.openpgp.org --send-keys <FINGERPRINT>
    ```

2. Put your Central Portal user token and the GPG passphrase in
   `~/.m2/settings.xml` (generate a token under your account at
   https://central.sonatype.com/account):

    ```xml
    <settings>
      <servers>
        <server>
          <id>central</id>
          <username>TOKEN_USERNAME</username>
          <password>TOKEN_PASSWORD</password>
        </server>
        <server>
          <id>gpg.passphrase</id>
          <passphrase>YOUR_GPG_PASSPHRASE</passphrase>
        </server>
      </servers>
    </settings>
    ```

## Release

```shell
mvn versions:set -DnewVersion={new_version}
mvn clean deploy -P release
```

With `<autoPublish>true</autoPublish>` the deployment is validated and
published automatically; no manual step in the web UI is needed. Otherwise, go
to https://central.sonatype.com/publishing, find the deployment in the
"Deployments" tab, and click "Publish".

### Known issue: "Bundle has content that does NOT have a .pom file"

Because NIOInspector uses `maven-plugin` packaging, the staged bundle can pick
up group-level `maven-metadata-local.xml` and `_remote.repositories` files,
which the Portal validator rejects with an error like:

    Bundle has content that does NOT have a .pom file: edu/illinois, edu/illinois/NIOInspector

Workaround - repackage the bundle without those files and upload it through
the Publisher API:

```shell
cd target/central-publishing
mkdir fixed && cd fixed && unzip ../central-bundle.zip
find . -name 'maven-metadata-local.xml*' -delete
find . -name '_remote.repositories*' -delete
zip -r ../central-bundle-fixed.zip edu && cd ..

TOKEN=$(printf 'TOKEN_USERNAME:TOKEN_PASSWORD' | base64)
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -F "bundle=@central-bundle-fixed.zip" \
  "https://central.sonatype.com/api/v1/publisher/upload?name=NIOInspector-{version}&publishingType=AUTOMATIC"
# returns a deployment id; poll until deploymentState is PUBLISHED:
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "https://central.sonatype.com/api/v1/publisher/status?id=<DEPLOYMENT_ID>"
```

`gpgconf --kill gpg-agent` between attempts may help if gpg was interrupted.
