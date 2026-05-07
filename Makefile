build-java:
	cd helpers/java && mvn compile -q

test-java:
	cd helpers/java && mvn test

build: build-java

test: test-java

clean:
	cd helpers/java && mvn clean -q
