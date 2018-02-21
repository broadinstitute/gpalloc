#!/bin/bash

# This script will nuke all gpalloc-created projects.
# You need to be gcloud authed as a @xxx.firecloud.org admin (where xxx = test or qa) in order for this to do anything.

# You should only run this in conjunction with dropping all tables on the corresponding gpalloc.

if [[ $# == 0 ]]; then
	echo "Provide a prefix. Typically you want some variant of gpalloc-[env]-[branch], e.g. nuke_projects.sh gpalloc-test-develop"
	exit 0
fi

gcloud projects list | grep $1 | cut -f 1 -d ' ' | xargs -I % gcloud projects delete % --quiet