# Local build — requires ANDROID_HOME set and JDK 11.
# Usage: make  OR  make clean

ANDROID_HOME ?= $(shell echo $$ANDROID_HOME)
ANDROID_JAR  := $(ANDROID_HOME)/platforms/android-34/android.jar
D8           := $(ANDROID_HOME)/build-tools/34.0.0/d8

SRC_DIR  := app/src/main/java
OUT_CLASSES := build/classes
OUT_DEX     := app/build/outputs/dex

SOURCES := $(shell find $(SRC_DIR) -name "*.java")

.PHONY: all clean

all: $(OUT_DEX)/classes.dex

$(OUT_CLASSES):
	mkdir -p $(OUT_CLASSES)

$(OUT_DEX):
	mkdir -p $(OUT_DEX)

$(OUT_DEX)/classes.dex: $(SOURCES) | $(OUT_CLASSES) $(OUT_DEX)
	javac -source 11 -target 11 -cp $(ANDROID_JAR) -d $(OUT_CLASSES) $(SOURCES)
	$(D8) --release --min-api 29 --output $(OUT_DEX) $(shell find $(OUT_CLASSES) -name "*.class")
	@echo "DEX ready: $(OUT_DEX)/classes.dex"

clean:
	rm -rf build $(OUT_DEX)
