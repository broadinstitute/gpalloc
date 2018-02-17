# How to use GPAlloc

Briefly, you call `GET /api/googleproject` to request a "clean" billing project, which will return something like this:

```json
{
  "projectName": "gpalloc-dev-master-ggukibs",
  "cromwellAuthBucketUrl": "cromwell-auth-gpalloc-dev-master-ggukibs"
}
```
 
When you're finished with your project, call `DELETE /api/googleproject/<projectName>` to return it to the pool.

If you don't do this, GPAlloc will assume you forgot to clean up after yourself and return it to the pool automatically after two hours.
 
The situation in FiaB-style auto-testing land is a little different in that workbench-libs does some of this for you. So let's talk about that.

## GPAlloc in FiaB auto-tests

Rawls + Sam have no way to forget that a billing project ever existed. Since releasing a claim on a billing project means you may get that billing project back in a later request to GPAlloc, we have to hold on to _all_ claimed billing projects until any given auto-test run is complete. (Otherwise you could potentially get a billing project you've already seen, and Rawls + Sam would barf saying "I already know about this project".)

`workbenchServiceTest` has some support for GPAlloc inbuilt. Mixing in `GPAllocFixtures` provides the loan-pattern method `withCleanBillingProject` that supplies a GPAlloc'd project (and falls back to creating a new one if none are available).

If you have a _single_ test that mixes in `GPAllocFixtures`, this will Just Work™.

If you have _multiple_ tests that mix in `GPAllocFixtures`, the need to release projects only once _all_ tests are finished complicates things a tiny bit. Here's how you get round it:

1. Make a new super-Suite with all of your suites nested inside it, like so:  
  
  ```
class SuperSuite extends Suites with GPAllocSuperFixture {
  new FooSpec,
  new BarSpec,
  new BazSpec
}
```  
  
  This test will run `FooSpec`, `BarSpec` and `BazSpec` in sequence; the mixed-in `GPAllocSuperFixture` will handle releasing all GPAlloc'd projects from its suites when it finishes.
  
2. Mark your individual test suites with the `@DoNotDiscover` annotation. This will prevent ScalaTest from running them twice: once as part of the super-Suite and then a second time when it discovers them again.
