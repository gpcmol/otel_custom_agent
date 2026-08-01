#!/usr/bin/env bash

./scripts/build.sh && java -jar benchmark/target/benchmark-jmh.jar --mode all
