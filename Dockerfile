FROM openjdk:8

EXPOSE 8080
EXPOSE 5050

ENV GIT_HASH $GIT_HASH

RUN mkdir /gpalloc
COPY ./gpalloc*.jar /gpalloc

# Add gpalloc as a service (it will start when the container starts)
CMD java $JAVA_OPTS -jar $(find /gpalloc -name 'gpalloc*.jar')
