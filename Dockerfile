# Builds the GraalVM native image and ships it on a slim Debian base.
#
# The Kotlin compilation uses the JDK 21 toolchain declared in build.gradle.kts;
# native-image comes from GraalVM CE for JDK 25, which also runs Gradle.
FROM ghcr.io/graalvm/native-image-community:25 AS build
ENTRYPOINT []
COPY --from=eclipse-temurin:21-jdk /opt/java/openjdk /opt/jdk-21
# native-image sizes its heap from the host's memory rather than the build container's
# limit, so cap it, and keep Gradle and the Kotlin compiler small, to fit a 6 GiB builder.
# Each compiler thread adds native memory on top of the heap, and with all eight the
# builder ran out during "Compiling methods"; four keep the peak inside the limit.
ENV GRAALVM_HOME=/usr/lib64/graalvm/graalvm-community-java25 \
    NATIVE_IMAGE_OPTIONS="-J-Xmx4g --parallelism=4"
RUN microdnf install -y findutils && microdnf clean all \
 && mkdir -p /root/.gradle \
 && printf 'org.gradle.java.installations.paths=/opt/jdk-21\norg.gradle.java.installations.auto-download=false\norg.gradle.jvmargs=-Xmx1g\nkotlin.compiler.execution.strategy=in-process\n' > /root/.gradle/gradle.properties
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version
COPY src src
# Compile first, so the Gradle that runs native-image starts fresh with a small heap:
# the classes are up to date by then, the Kotlin compiler is never loaded, and the
# memory it held is left to native-image.
RUN ./gradlew --no-daemon classes
RUN ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx512m nativeCompile

FROM debian:bookworm-slim
WORKDIR /app
COPY --from=build /src/build/native/nativeCompile/storm-imdb-ktor-graalvm /app/storm-imdb-ktor-graalvm
# A hosted database is loaded from a dump of a local import: the initial import blocks
# startup for longer than hosting platforms wait. With data present, startup syncs in the
# background instead, and the daily sync keeps the data current from there.
ENV PORT=8080
EXPOSE 8080
CMD ["/app/storm-imdb-ktor-graalvm"]
