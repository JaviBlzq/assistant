# Third-party notices

The MIT license covers the original project code. Third-party assets and dependencies retain their own licenses.

- **Doto** — Copyright 2024 The Doto Project Authors. SIL Open Font License 1.1; see [Doto-OFL.txt](licenses/Doto-OFL.txt). Source: https://github.com/google/fonts/tree/main/ofl/doto. Used only for section/card titles.
- **Mozilla CA roots** — the 118-certificate PEM bundle at `app/src/main/assets/certs/legacy-tls-roots.pem` is public trust-anchor material, not private keys. Mozilla trust-store material is distributed under MPL 2.0; see [Mozilla-MPL-2.0.txt](licenses/Mozilla-MPL-2.0.txt). Upstream: https://curl.se/docs/caextract.html and https://www.mozilla.org/MPL/2.0/. Review upstream trust changes before updating this snapshot.
- **Gradle wrapper** — Gradle, Apache License 2.0: https://github.com/gradle/gradle/blob/master/LICENSE.
- **Conscrypt**, **Java-WebSocket**, **SLF4J**, **ZXing**, **JUnit** and **JSON-java** are resolved by Gradle and retain their upstream licenses. Versions are declared in `app/build.gradle.kts`.
- Maps and route data may use OpenStreetMap contributors and OSRM; preserve map attribution and observe provider terms. External API access and satellite catalog data remain subject to their respective providers' terms.

Legacy Tecnica font files are excluded from the public source and are not required by the application.
