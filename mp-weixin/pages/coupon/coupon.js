var baseUrl = 'http://localhost:8080';

Page({
  data: {
    couponList: [],
    myCoupons: [],
    loaded: false
  },

  onShow: function() {
    this.loadCoupons();
    this.loadMyCoupons();
  },

  loadCoupons: function() {
    var token = wx.getStorageSync('sky_token') || '';
    wx.request({
      url: baseUrl + '/user/coupon/list',
      method: 'GET',
      header: { 'authentication': token },
      success: function(res) {
        if (res.data && res.data.code === 1) {
          this.setData({ couponList: res.data.data || [], loaded: true });
        } else {
          this.setData({ loaded: true });
        }
      }.bind(this),
      fail: function() {
        wx.showToast({ title: '网络错误', icon: 'none' });
        this.setData({ loaded: true });
      }.bind(this)
    });
  },

  loadMyCoupons: function() {
    var token = wx.getStorageSync('sky_token') || '';
    wx.request({
      url: baseUrl + '/user/coupon/my',
      method: 'GET',
      header: { 'authentication': token },
      success: function(res) {
        if (res.data && res.data.code === 1) {
          this.setData({ myCoupons: res.data.data || [] });
        }
      }.bind(this)
    });
  },

  grabCoupon: function(e) {
    var couponId = e.currentTarget.dataset.id;
    var token = wx.getStorageSync('sky_token') || '';

    wx.showLoading({ title: '抢券中...' });

    wx.request({
      url: baseUrl + '/user/coupon/grab/' + couponId,
      method: 'POST',
      header: { 'authentication': token },
      success: function(res) {
        wx.hideLoading();
        if (res.data && res.data.code === 1) {
          wx.showToast({ title: '抢券成功！', icon: 'success' });
          this.loadCoupons();
          this.loadMyCoupons();
        } else {
          wx.showToast({ title: res.data.msg || '抢券失败', icon: 'none' });
        }
      }.bind(this),
      fail: function() {
        wx.hideLoading();
        wx.showToast({ title: '网络错误', icon: 'none' });
      }
    });
  }
});
