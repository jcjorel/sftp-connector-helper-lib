build-java:
	cd helpers/java && mvn compile -q

test-java:
	cd helpers/java && mvn test

build-lambdas:
	@set -e; for dir in lambdas/*/; do \
		cd $$dir && \
		rm -rf dist/ && \
		uv export --format requirements-txt --no-dev --no-emit-project -o requirements.txt && \
		pip install --target dist/ -r requirements.txt -q && \
		cp *.py dist/ && \
		cd ../..; \
	done

build: build-java build-lambdas

test: test-java

clean:
	cd helpers/java && mvn clean -q
