# 1. Use an official Maven image with Java 22 to build your app
FROM maven:3.9.6-eclipse-temurin-22 AS build
WORKDIR /app

# 2. Copy your raw code into the container
COPY pom.xml .
COPY src ./src

# 3. Build the Fat Jar
RUN mvn clean package

# 4. Command to run the game server when the container starts
CMD ["java", "-jar", "target/DahlaPakad-1.0-SNAPSHOT-jar-with-dependencies.jar"]