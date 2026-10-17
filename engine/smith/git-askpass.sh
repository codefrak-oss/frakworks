#!/bin/sh
# GIT_ASKPASS: answers git's credential prompts from the environment so the
# token never lands in a URL, a config file or `ps`.
case "$1" in
  Username*) echo "x-access-token" ;;
  Password*) echo "${GH_TOKEN:?GH_TOKEN is not set}" ;;
esac
