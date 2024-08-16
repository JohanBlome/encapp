#!/usr/bin/env bash
id=$1
if [[ -z $id ]]; then
    echo "Usage: $0 <id> [platform], example: "
    echo ">$0 00001234-123456789012"
    echo ">$0 ABCD1234-AB12-CD34-EF56-ABCD1234 macOS"
    echo "Unless platform is set iOS is assumed"
    exit 1
fi

platform="iOS"
if [[ ! -z $2 ]]; then
    platform=$2
fi

scheme=$(xcodebuild -list  | grep -A 1 Schemes | grep -v Schemes)
echo "Using $scheme"
xcodebuild -scheme ${scheme} clean -quiet  
echo "Install ${platform} app to ${id}"
xcodebuild -scheme ${scheme} install -destination 'platform=${platform},id=${id}'  -allowProvisioningUpdates 

