python_path=../scripts/proto/
swift_path=../ios/Encapp/

# Pin to protoc 3.20.3 to match gradle protobuf library compatibility
# This prevents version conflicts when different developers have different protoc versions
# Note: 3.20.x is compatible with 3.24.0 protobuf libraries (backward/forward compatible within 3.x)
PROTOC_VERSION=3.20.3
PROTOC_DIR=./protoc-$(PROTOC_VERSION)
PROTOC=$(PROTOC_DIR)/bin/protoc

# Detect OS and architecture for downloading the correct protoc binary
UNAME_S := $(shell uname -s)
UNAME_M := $(shell uname -m)
ifeq ($(UNAME_S),Linux)
    ifeq ($(UNAME_M),x86_64)
        PROTOC_ZIP=protoc-$(PROTOC_VERSION)-linux-x86_64.zip
    else ifeq ($(UNAME_M),aarch64)
        PROTOC_ZIP=protoc-$(PROTOC_VERSION)-linux-aarch_64.zip
    endif
else ifeq ($(UNAME_S),Darwin)
    # macOS uses x86_64 binary (works on both Intel and Apple Silicon via Rosetta 2)
    PROTOC_ZIP=protoc-$(PROTOC_VERSION)-osx-x86_64.zip
endif
PROTOC_URL=https://github.com/protocolbuffers/protobuf/releases/download/v$(PROTOC_VERSION)/$(PROTOC_ZIP)

# java will be generated when building with gradle
all: $(PROTOC) tests.proto
	$(PROTOC) -I=. --python_out=$(python_path) tests.proto
	$(PROTOC) -I=. --swift_out=$(swift_path) tests.proto
	@echo "Successfully generated protobuf files using protoc $(PROTOC_VERSION)"

# Download and extract the pinned protoc version if not present
$(PROTOC):
	@echo "Downloading protoc $(PROTOC_VERSION) for $(UNAME_S)/$(UNAME_M)..."
	@mkdir -p $(PROTOC_DIR)
	@curl -L -o $(PROTOC_ZIP) $(PROTOC_URL)
	@unzip -o $(PROTOC_ZIP) -d $(PROTOC_DIR)
	@rm $(PROTOC_ZIP)
	@chmod +x $(PROTOC)
	@echo "protoc $(PROTOC_VERSION) installed to $(PROTOC_DIR)"

clean:
	rm -f *pyc

realclean: clean
	rm -f $(python_path)tests_pb2.py
	rm -f $(swift_path)tests.pb.swift
	rm -rf $(PROTOC_DIR)
