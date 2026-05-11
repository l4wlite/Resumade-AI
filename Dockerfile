# Multi-stage Dockerfile for building and running the resumade-monolith Spring Boot app

# Builder image (uses JDK 21 to match project)
FROM maven:3.9.4-eclipse-temurin-21 AS builder
WORKDIR /workspace

# Copy Maven POM and source
COPY pom.xml ./

# Copy source and build
COPY src ./src
RUN mvn -B -DskipTests clean package

# Runtime image
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy executable jar from builder
COPY --from=builder /workspace/target/*.jar app.jar

EXPOSE 9090
ENV JAVA_OPTS="-Xms256m -Xmx512m"

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
