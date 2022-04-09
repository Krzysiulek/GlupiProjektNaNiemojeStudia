## Spring Data Redis

### Relevant Articles:
- [Introduction to Spring Data Redis](https://www.baeldung.com/spring-data-redis-tutorial)
- [PubSub Messaging with Spring Data Redis](https://www.baeldung.com/spring-data-redis-pub-sub)
- [An Introduction to Spring Data Redis Reactive](https://www.baeldung.com/spring-data-redis-reactive)

### Build the Project with Tests Running
```
mvn clean install
```

### Run Tests Directly
```
mvn test
```


### Odpalanie redisa:
```
docker run -p 8989:6379 -d redis:6.0 redis-server
```