SHELL = bash
GRADLEW = ./gradlew
APP_ID = com.sully_vian.gitkeep
CYAN = $(shell tput setaf 6)
RESET = $(shell tput sgr0)

.PHONY: help build test install run logs clean

.DEFAULT_GOAL := help

help: ## Show this help message
	@rg '^([ a-zA-Z_-]+): [ a-zA-Z_-]*## (.*)$$' -r $$'$(CYAN)$$1$(RESET)\t$$2' $(MAKEFILE_LIST) | column -t -s $$'\t'

build: ## Debug APK, no phone needed
	$(GRADLEW) :app:assembleDebug

test: ## JVM unit tests (no phone needed)
	$(GRADLEW) test --rerun

install: ## Build and install the debug APK on the phone
	$(GRADLEW) :app:installDebug

run: install ## Build, install and launch on the phone
	adb shell am start -n $(APP_ID)/.MainActivity

logs: ## Logs from the running app
	adb logcat --pid $$(adb shell pidof -s $(APP_ID))

clean: ## Delete build outputs
	$(GRADLEW) clean
