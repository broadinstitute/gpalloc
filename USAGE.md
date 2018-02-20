# How to use GPAlloc

Briefly, you call `GET /api/googleproject` to request a "clean" billing project, which will return something like this:

```json
{
  "projectName": "gpalloc-dev-master-ggukibs",
  "cromwellAuthBucketUrl": "cromwell-auth-gpalloc-dev-master-ggukibs"
}
```

You can give this info to the [project registration endpoint](https://rawls.dsde-dev.broadinstitute.org/#!/admin/recordProjectOwnership) of a Rawls; that Rawls will do its own setup and convince itself that it was the one who created the project. Once the endpoint returns (it'll take a few seconds to sync google groups) the project should behave completely normally.
 
When you're finished with your project, call `DELETE /api/googleproject/<projectName>` to return it to the pool.

If you don't return it to the pool within two hours, GPAlloc will assume you forgot to clean up after yourself and do it for you.
 
The situation in FiaB-style auto-testing land is a little different in that workbench-libs does some of this for you. So let's talk about that.

## GPAlloc in FiaB auto-tests

Rawls + Sam have no way to forget that a billing project ever existed. Since releasing a claim on a billing project means you may get that billing project back in a later request to GPAlloc, we have to hold on to _all_ claimed billing projects until any given auto-test run is complete. (Otherwise you could potentially get a billing project you've already seen, and Rawls + Sam would barf saying "I already know about this project".)

`workbenchServiceTest` now has support for GPAlloc. Mixing in `GPAllocFixtures` provides the loan-pattern method `withCleanBillingProject`. This supplies a GPAlloc'd project and handles the cleanup at the end for you. (If no GPAlloc'd projects are available, it falls back to creating a new one.)

If you have a _single_ test that mixes in `GPAllocFixtures`, this will Just Work™.

If you have _multiple_ tests that mix in `GPAllocFixtures`, the need to release projects only once _all_ tests are finished complicates things a tiny bit. The good news is that if you do it wrong, the fixtures will notice and fail loudly.

Here's how to set up your tests so they all work:

1. Make a new super-Suite with all of your suites nested inside it, like so:  
  
  ```
class MySuperSuite extends Suites(
  new FooSpec,
  new BarSpec,
  new BazSpec) with GPAllocSuperFixture {

}
```  
  
  This test will run `FooSpec`, `BarSpec` and `BazSpec` in sequence; the mixed-in `GPAllocSuperFixture` will handle releasing all GPAlloc'd projects from its suites when it finishes.
  
2. Mark your individual test suites with the `@DoNotDiscover` annotation. This will prevent ScalaTest from running them twice: once as part of the super-Suite and then a second time when it discovers them again.

You can still run the `@DoNotDiscover`ed tests by right-clicking on them in Intellij, or asking for them explicitly with `testOnly`. Running `sbt test` from the commandline will run everything as you'd expect.
