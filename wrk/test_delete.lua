require("init")

counter = 0
request = function()
   path = "/v0/entity?id=" .. counter
   counter = counter + 1
   return wrk.format("DELETE", path)
end
