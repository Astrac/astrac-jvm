'use strict';

angular.module('uiApp')
  .controller('MainCtrl', function ($scope, $http, $interval) {
    var errors = 0;
    var stop = $interval(function() {
      $http({method: 'GET', url: '/api/status'})
        .success(function(data) {
          var stat = {
            "extracted": data.extracted,
            "transformed": data.transformed,
            "loaded": data.loaded,
            "extractFailures": data.extractFailures,
            "transformFailures": data.transformFailures,
            "loadFailures": data.loadFailures,
            "unknownFailures": data.unknownFailures,
            "runtime": data.runtime,
            "totalRecords": data.totalRecords,
            "extimatedRemainingTime": data.extimatedRemainingTime
          }

          if (data.totalRecords) {
            stat.percentageExtracted = ((data.extracted / data.totalRecords) * 100).toString() + "%";
            stat.percentageTransformed = ((data.transformed / data.totalRecords) * 100).toString() + "%";
            stat.percentageLoaded = ((data.loaded / data.totalRecords) * 100).toString() + "%";
          } else {
            stat.percentageExtracted = "100%";
            stat.percentageTransformed = "100%";
            stat.percentageLoaded = "100%";
          }

          $scope.stat = stat;
        })
        .error(function(data) {
          if (angular.isDefined(stop)) {
            errors++;
            console.log('Status request error (errors count: ' + errors + ')');

            if (errors >= 4) {
              console.log('Too many errors, cancelling update loop');
              $interval.cancel(stop);
              errors = 0;
            }
          }
        });
    }, 1000);
  });
