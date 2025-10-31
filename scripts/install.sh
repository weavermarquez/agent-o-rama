#!/bin/sh

set -eu

rm -rf target
sh scripts/build-ui.sh
lein install
