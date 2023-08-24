FROM maven:3.9.3-amazoncorretto-11
RUN mkdir /app
COPY ./src /app/src
COPY ./pom.xml /app
WORKDIR /app
RUN mvn clean test package

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/target/sync-1.0-SNAPSHOT.jar"]