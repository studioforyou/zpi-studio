# Etap 1: Budowanie
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
# Kopiujemy pom.xml i pobieramy zależności (przyspiesza kolejne buildy)
COPY pom.xml .
RUN mvn dependency:go-offline
# Kopiujemy kod i budujemy JAR
COPY src ./src
RUN mvn clean package -DskipTests

# Etap 2: Uruchamianie
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]