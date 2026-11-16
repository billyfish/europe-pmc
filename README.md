# Dockerised Apache Tomcat

Tomcat 11 on Eclipse Temurin JRE 21, running as a non-root user with a healthcheck.
The image builds and deploys the `pmc` Java webapp (Maven, Jakarta Servlet 6.1) at the root context.

## API

`GET /search?institution=<institution>&limit=<n>&publicationsPerGrant=<n>`

| Parameter              | Required | Default | Rules                      |
|------------------------|----------|---------|----------------------------|
| `institution`          | yes      | –       | non-blank, one value only  |
| `limit`                | no       | 5       | number of grants, 1–10     |
| `publicationsPerGrant` | no       | 3       | publications per grant, 1–5 |
| `page` | no       | 1      | the page number of results to get |

A blank `limit` or `publicationsPerGrant` (_e.g._ `limit=`) uses the default and unknown parameters are ignored.

On success, it returns HTTP 200 with JSON:

```json
{"institution":"Earlham","limit":5,"publicationsPerGrant":3,"grants":[],"publications":[]}
```

Invalid input returns HTTP 400 with one message per problem, e.g.

```json
{"errors":["'limit' must be between 0 and 10","'publicationsPerGrant' must be an integer"]}
```

The servlet running on `/` serves a simple HTML form for trying it out.

## Layout

| Path       | Purpose                                                          |
|------------|------------------------------------------------------------------|
| `src/`     | Java webapp source (`SearchServlet`, `SearchRequest`) and tests |
| `pom.xml`  | Maven build (Servlet API, Jackson, JUnit); the Docker build stage runs `mvn package` |
| `conf/`    | Optional overrides for files in `$CATALINA_HOME/conf` (e.g. `server.xml`, `tomcat-users.xml`) |



## Building the project

The project uses Maven as its build tool and it has the following options:

 * `mvn clean`: clean any built code 
 * `mvn test`: run the unit tests 
 * `mvn package`: build `target/pmc.war`.

In Eclipse, use **Maven → Update Project** after changing `pom.xml`.


## Running the project

The project is run from Docker and there are two ways to  build the image:

1. Without Compose

```sh
docker build -t institution-portfolio .
docker run --rm -p 8080:8080 institution-portfolio
```

2. With Compose

```sh
docker compose up -d --build     # build and start
open http://localhost:8080       # form
curl 'http://localhost:8080/search?institution=Earlham&limit=5&publicationsPerGrant=3'
docker compose logs -f tomcat    # follow logs
docker compose down              # stop
```

You can use a different Tomcat/Java version with a build arg, _e.g._
`docker build --build-arg TOMCAT_TAG=10.1-jre17-temurin -t pmc-tomcat .`



## Examples


Once the Docker image is running, you can access the servlet  directly or via the simple form at http://localhost:8080.

You can also call the REST API directly and it is of the form

`http://localhost:8080/search`

For instance, `http://localhost:8080/search?institution=Cambridge&limit=5&publicationsPerGrant=3` will search for all grants mentioning Cambridge as part of the affiliated institution and return a maximum of 5 grants, each with a a maximum of 3 publications.

Alternatively to search for Sheffield University, the query would be 
`http://localhost:8080/search?institution=sheffield+university&limit=8&publicationsPerGrant=4`

The above searches appear to be retrieving the matching grants in date order, starting with the latest ones, which do not have publications. So I added a `page` parameter (described in the assumptions about PMC data section below) to make sure that, where publications exist, the code retrieves them correctly.

For instance, to search for earlier grants and their publications, you can set the `page` parameter _e.g._
`http://localhost:8080/search?institution=sheffield+university&limit=8&publicationsPerGrant=4&page=10`

## Design

I designed it so that there is a main Class, `PmcSearch` which manages the process. It parses the input parameters and makes sure that they are valid. If they are, it then calls its `GrantsSearch` child object which performs the initial part of the search, calling the EuropePMC servers to get back the matching grants.  I took the approach that the grant id was unique for a given funder but not across other funders and `GrantsSearch.isNewGrant ()` is the method that checks for this. 
Due to the GristAPI initially being publicly unavailable, I used the EuropePMC general search API instead. 
Once the GristAPI was available again I left my original code in as an alternative option to compare and contrast but switched to using the GristAPI as the default. For each matching  grant, I then created a class called `ArticlesSearch` to search for all articles for that grant using the Articles API. As we will almost certainly have multiple grants, I created an instance of `ArticlesSearch` for each grant  and they are all managed within an `ArticlesSearchSet` object.

In terms of concurrency, I went for the approach of the initial grants-based search running sequentially until it has found the requested number of grants or made a maximum number of calls to the the EuropePMC server (this is set by `maxNumCalls` in `BaseSearch.collateResults ()`).  
Once they have completed, the subsequent searches to the Articles API are all run asynchronously and then we wait until they’re all complete before collating all of the results.

For error handling, I created a simple interface, `ErrorReceiver`, that any class can potentially send error messages to, collecting them all in one place. In this initial design, `PmcSearch` is the ErrorReceiver so it collects all error messages which makes it straightforward to add them to the JSON response that it sends back to the `SearchServlet` or to the direct call to its REST API. Any error messages are then added to the `errors.errorList` of the `PmcSearch`’s  response.

I took a very simplistic approach to institution-matching, with the `InstitutionNameNormaliser` class that essentially matches and names that are “X university” to  also be “University of X”.  There are also a list of exceptions to this rule and a non-comprehensive list of those is within that class.

## Review of my solution

### The  design decision that worked particularly well

I was pleased with the overall architecture of the initial grants search running, followed by spawning asynchronous calls for the articles for each grant as it seems to run at a decent speed, whilst keeping o a maximum number of threads felt like a nice system. Obviously these things can always be improved on and I would look to make the code more modular. 

### What would you change or improve with more time?

I would like to add the ability to filter out grants that have not began as they would not have publications.  
It seems like the calls to the GrantsAPI  return results in data order, so it would be good to find a way of sorting these results in a different order.

I would definitely continue to refactor the code so that the searches for articles would also be subclasses of `BaseSearch` as the general principle is the same _i.e_ call a web service, find the results and iterate over them to extract the information that you need.  

One thing I would improve about the `GrantsSearch` is for item `isNewGrant ()` method  to also take any entries in the `Alias` part of the JSON returned for the `Grant` by the GristAP  into account in case the the same grant might be being referred to by its `Id` being differing ones of these aliases which my code currently would treat as different grants .


### Which assumption about Europe PMC data would you validate before production?

I would become more familiar with the API options to see if _e.g._ there are ways of sorting the results that come back from the GristAPI. 
They _appear_ to come back in a “by date” order which has the side effect that the most recent grants, especially those which either just have started or are yet to begin, will not tend to have any publications.  
When the GristAPI was publicly unavailable, and I used the general search API instead, this didn’t appear to be the case and so getting Grants that had publications was easier. 
This is why I added the `page` parameter to the search for an easy way to  get to earlier grants which were consequently more likely to have publications associated with them.

### Which piece of technical debt did you deliberately accept?

Ideally refactoring the Search classes so that they both shared a common parent (`BaseSearch`) rather than `ArticlesSearch` being a separate entity. 
I also accepted `PmcSearch` doing the heavy lifting in terms of building the response rather than the neater approach of letting the articles and grants adding themselves to the response by themselves via their Search classes.  Similarly having all of the error messages stored within the `PmcSearch` object, rather than each object storing its own error messages that can then be retrieved at the end, is not an approach that I’d like to keep.  Each object being its own JSON and errors to the final response is a much cleaner approach that I normally use.


