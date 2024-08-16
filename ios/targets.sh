#!/usr/bin/env bash
#
echo "List available targets"
scheme=$(xcodebuild -list  | grep -A 1 Schemes | grep -v Schemes)
echo "Using $scheme"
echo "Available targets"
xcodebuild -showdestinations -workspace ./Encapp.xcodeproj/project.xcworkspace -scheme ${scheme} | grep "{ "


