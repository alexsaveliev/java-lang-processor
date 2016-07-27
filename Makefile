ifeq ($(OS),Windows_NT)
	GRADLEW = .\gradlew.bat
else
	GRADLEW = ./gradlew
endif

SRC = $(shell /usr/bin/find ./src -type f)

.PHONY: default install clean

default: install

build/libs/java-lang-processor-0.0.1-SNAPSHOT.jar: build.gradle ${SRC}
	${GRADLEW} jar

.bin/java-lang-processor.jar: build/libs/java-lang-processor-0.0.1-SNAPSHOT.jar
	cp build/libs/java-lang-processor-0.0.1-SNAPSHOT.jar .bin/java-lang-processor.jar

install: .bin/java-lang-processor.jar

clean:
	rm -f .bin/java-lang-processor.jar
	rm -rf build
