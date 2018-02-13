#!/bin/bash

# This script will nuke all gpalloc-created projects.
# You need to be gcloud authed as a @xxx.firecloud.org admin (where xxx = test or qa) in order for this to do anything.

# You should only run this in conjunction with dropping all tables on the corresponding gpalloc.

gcloud projects list | grep gpalloc | cut -f 1 -d ' ' | xargs -I % gcloud projects delete % --quiet