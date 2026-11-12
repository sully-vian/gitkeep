SHELL = bash
GRADLEW = cd android && ./gradlew
CAP = bunx cap
APP_ID = com.sully_vian.gitkeep
CYAN = $(shell tput setaf 6)
RESET = $(shell tput sgr0)

.PHONY: help install serve sync build run logs clean

.DEFAULT_GOAL := help

help: ## Show this help message
	@rg '^([ a-zA-Z_-]+): [ a-zA-Z_-]*## (.*)$$' -r $$'$(CYAN)$$1$(RESET)\t$$2' $(MAKEFILE_LIST) | column -t -s $$'\t'

install: ## Install JS dependencies
	bun install

serve: ## UI work in desktop Chrome
	bunx serve www

sync: install ## Copy www/ and plugins into android/
	$(CAP) sync android

build: sync ## Debug APK, no phone needed
	$(GRADLEW) assembleDebug

run: sync ## Build, install and launch on the phone
	$(CAP) run android

logs: ## Native logs from the app
	adb logcat --pid $$(adb shell pidof -s $(APP_ID))

clean: ## Clean android/
	$(GRADLEW) clean
