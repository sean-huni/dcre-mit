FROM eclipse-temurin:25-jre-alpine
COPY build/libs/mis-2.0.jar /app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
