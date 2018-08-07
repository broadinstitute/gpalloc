# GPAlloc FAQ

## Common errors

### `Cannot create billing project [gpalloc-foo] in database because it already exists`

#### What probably happened

1. Your test environment requested a project from GPAlloc, got `gpalloc-foo`, and used it to run some tests.
2. Your test environment told GPAlloc it was done with `gpalloc-foo`, but failed to remove it from its Rawls database.
3. Your test environment requested another project from GPAlloc, and got `gpalloc-foo` again.
4. Your test environment attempted to add `gpalloc-foo` to its Rawls database, which failed because the project already exists in the database.

The problem here is at step #2. Most likely either the Rawls cleanup failed, or the test cleanup was never run at all.

#### What should happen

Assuming you're using `withCleanBillingProject`, the sequence of function calls looks like this:

```
BillingFixtures.withCleanBillingProject
    BillingFixtures.claimGPAllocProject
        GPAlloc.requestProject
        Rawls.admin.claimProject
        
    //your test code runs here
    
    BillingFixtures.releaseGPAllocProject
        Rawls.admin.releaseProject
        GPAlloc.releaseProject
```

Your test may not be using `withCleanBillingProject` may instead be handling the calls to `BillingFixtures.claimGPAllocProject` and `BillingFixtures.releaseGPAllocProject` itself, but the sequence is the same: claim, use, release.

#### What do I do?

Go to your test log and search for `gpalloc-foo`. You'll see at least two claim-and-release cycles that used that project, possibly more.

A single claim-and-release cycle looks like this. It'll be spread out across the log, and the stuff in square brackets might be different depending on what tests you're running (these logs were stolen from a UI run), but the words on the right should look familiar:

```
[GPAlloc$ :requestProject] GPAlloc returned new project gpalloc-qa-master-0h7scym
[Rawls$ :claimProject] Claiming ownership of billing project: gpalloc-qa-master-0h7scym

...your test uses this project a bunch of times...

[Rawls$ :releaseProject] Releasing ownership of billing project: gpalloc-qa-master-0h7scym
[GPAlloc$ :releaseProject] Releasing project gpalloc-qa-master-0h7scym
```

Find the one that threw your `Cannot create billing project` error (likely the last one in the log). The claim-and-release cycle immediately _before_ that one is the one that caused you problems.

At this point you will have to put your debugging hat on. You are looking for one of two things:

1. Something indicating that the workbench-libs cleanup code failed or didn't run; or
2. Something indicating the call to `Rawls.releaseProject` failed.

If you suspect #1, congratulations! Fix the cleanup issue in your test and you'll be on your way.

If you suspect #2, you will need to check the Rawls logs for that test run. The sequence of calls to check:

```
AdminApiService receives DELETE /admin/project/registration/gpalloc-foo
    AdminApiService sends AdminUnregisterBillingProject message to UserService
        UserService.unregisterBillingProject
            samDAO.deleteResource
            dataAccess.rawlsBillingProjectQuery.delete
```

i.e. code [around here](https://github.com/broadinstitute/rawls/blob/a15ab9992fa602912997ad3ebb68d56a30ae7842/core/src/main/scala/org/broadinstitute/dsde/rawls/user/UserService.scala#L297).

In the past we've seen the call to `samDAO.deleteResource` fail because of transient Google errors, which breaks out of the for-comp and fails to run the database cleanup. If the issue is indeed something around here, talk to the Cloud Accounts team and see if you (or they) can get it fixed.
